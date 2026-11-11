package com.aura.music.core

import android.net.Uri
import android.os.Looper
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.WeakHashMap

/** Persistent keys contain no session credential, and never depend on a supplied customCacheKey. */
internal object PrivateStreamCache {
    private const val PREFIX = "aura-private-v2"
    private const val OWNED_FORMAT = "custom_aura_private_format"
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun origin(base: URI) = URI("https", null, base.host.lowercase(),
        if (base.port == -1) 443 else base.port, null, null, null).toASCIIString()
    fun resource(base: URI, trackId: String) = "$PREFIX|${digest(origin(base))}|${Uri.encode(trackId)}"
    fun key(base: URI, userId: String, trackId: String, generation: String = "0") =
        "$PREFIX|${digest(origin(base))}|${digest(userId)}|${Uri.encode(trackId)}" +
            if (generation == "0") "" else "|$generation"
    /** Legacy customCacheKey can imitate a name, but cannot set this app-only metadata field. */
    fun ensureOwned(cache: Cache, key: String) {
        if (cache.getContentMetadata(key).get(OWNED_FORMAT, 0L) != 2L) {
            cache.removeResource(key)
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(OWNED_FORMAT, 2L))
        }
    }
    private fun matches(key: String, base: URI, id: String): Boolean {
        val parts = key.split('|')
        return parts.size in 4..5 && parts[0] == PREFIX && parts[1] == digest(origin(base)) &&
            parts[2].matches(Regex("[0-9a-f]{64}")) && parts[3] == Uri.encode(id)
    }

    class Revision(var namespace: String) { var value = 0L; var blocked = false }
    private val revisions = WeakHashMap<Cache, MutableMap<String, Revision>>()
    @Synchronized fun revision(cache: Cache, base: URI, id: String, store: PrivateCacheGenerationStore): Revision =
        revisions.getOrPut(cache) { mutableMapOf() }.getOrPut(resource(base, id)) {
            Revision(store.read(cache.uid, resource(base, id)))
        }

    /** Call on IO. The resource monitor precedes auth's memory-only scope checks, never the reverse. */
    fun evict(cache: Cache, base: URI, trackId: String, store: PrivateCacheGenerationStore) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Cache eviction requires IO" }
        val revision = revision(cache, base, trackId, store)
        synchronized(revision) {
            revision.blocked = true
            revision.value++
            // A failed removal (or a late native writer) cannot make old spans reachable after restart.
            revision.namespace = store.advance(cache.uid, resource(base, trackId))
            cache.keys.filter { matches(it, base, trackId) }.forEach(cache::removeResource)
            revision.blocked = false
        }
    }
}

/** Scope guard is OUTSIDE CacheDataSource, so even disk-only reads must be authorized. */
internal class PrivateCacheDataSource(
    private val auth: AuthSessionManager,
    private val origin: URI,
    private val cache: Cache,
    private val localFactory: DataSource.Factory,
    private val generations: PrivateCacheGenerationStore,
    private val cacheReadFactory: DataSource.Factory = FileDataSource.Factory(),
    private val upstream: (AuthSessionManager.Scope) -> DataSource
) : DataSource {
    private val policy = PrivateStreamPolicy(origin)
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null
    private var scope: AuthSessionManager.Scope? = null
    private var revision: PrivateStreamCache.Revision? = null
    private var generation = 0L
    private var key: String? = null
    private var scratch = ByteArray(0)

    override fun addTransferListener(listener: TransferListener) {
        listeners.add(listener); delegate?.addTransferListener(listener)
    }
    override fun open(dataSpec: DataSpec): Long {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("Chargement audio hors thread de travail")
        close()
        val remote = dataSpec.uri.scheme.equals("https", true) || dataSpec.uri.scheme.equals("http", true)
        if (!remote) {
            return localFactory.createDataSource().also { next ->
                delegate = next; listeners.forEach(next::addTransferListener)
            }.open(dataSpec)
        }
        val track = runCatching { policy.trackId(URI(dataSpec.uri.toString())) }.getOrNull()
            ?: throw IOException("Source audio non autorisée")
        val captured = auth.captureScope()
        scope = captured
        val resource = PrivateStreamCache.revision(cache, origin, track, generations).also { revision = it }
        try {
            return synchronized(resource) {
                if (resource.blocked) throw IOException("Invalidation audio incomplète")
                generation = resource.value
                auth.requireCurrent(captured)
                val cacheKey = PrivateStreamCache.key(origin, captured.userId, track, resource.namespace).also { key = it }
                PrivateStreamCache.ensureOwned(cache, cacheKey)
                val next = CacheDataSource.Factory().setCache(cache)
                    .setCacheReadDataSourceFactory(cacheReadFactory)
                    .setCacheKeyFactory { cacheKey }
                    .setUpstreamDataSourceFactory { upstream(captured) }
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource().also { delegate = it; listeners.forEach(it::addTransferListener) }
                val result = next.open(dataSpec.buildUpon().setKey(cacheKey).build())
                auth.requireCurrent(captured)
                result
            }
        } catch (error: Exception) { close(); throw error }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val captured = scope ?: return checkNotNull(delegate).read(buffer, offset, length)
        val resource = checkNotNull(revision)
        return synchronized(resource) {
            auth.requireCurrent(captured)
            if (resource.value != generation) throw IOException("Source audio réassignée")
            // Never let a stale native/disk read write directly into the caller's buffer.
            val size = minOf(length, 64 * 1024)
            if (scratch.size < size) scratch = ByteArray(size)
            val count = checkNotNull(delegate).read(scratch, 0, size)
            auth.copyIfCurrent(captured, scratch, buffer, offset, count)
            count
        }
    }
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> =
        if (scope?.let { !auth.isCurrent(it) } == true) emptyMap() else delegate?.responseHeaders.orEmpty()
    override fun close() {
        val resource = revision
        fun finish() {
            try { delegate?.close() } finally {
                // A sink close may commit its last fragment: remove it before another reader can open.
                try {
                    if (resource != null && resource.value != generation) {
                        try { key?.let(cache::removeResource) }
                        catch (failure: Exception) { resource.blocked = true; throw failure }
                    }
                } finally {
                    delegate = null; scope = null; revision = null; key = null
                    scratch.fill(0)
                }
            }
        }
        if (resource == null) finish() else synchronized(resource) { finish() }
    }
}
