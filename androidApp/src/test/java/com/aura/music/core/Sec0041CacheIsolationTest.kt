package com.aura.music.core

import android.app.Application
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.Closeable
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class, manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class Sec0041CacheIsolationTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private inner class World : Closeable {
        val tls = LoopbackAudioFixture()
        val store = MemorySessionStore(StoredSession(token("A"), "synthetic-refresh", "A", null))
        var refresh: suspend (String) -> Result<com.aura.music.data.network.SupabaseSessionDto> = { error("Unexpected refresh") }
        val auth = manager(store) { refresh(it) }
        val folder = temp.newFolder()
        var database = StandaloneDatabaseProvider(context)
        var cache = SimpleCache(folder, LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024), database)
        var denyB = false
        var version = ""
        val origin = tls.endpoint { request ->
            val owner = if (request.authorization == "Bearer ${token("B")}") "B" else "A"
            if (owner == "B" && denyB) LoopbackAudioFixture.Reply(403, byteArrayOf()) else {
                val data = (owner.repeat(12) + version).toByteArray()
                val range = request.range?.removePrefix("bytes=")?.split('-')
                val start = range?.get(0)?.toInt() ?: 0
                val end = range?.get(1)?.takeIf { it.isNotEmpty() }?.toInt() ?: data.lastIndex
                LoopbackAudioFixture.Reply(if (range == null) 200 else 206, data.copyOfRange(start, minOf(end + 1, data.size)),
                    if (range == null) emptyMap() else mapOf("Content-Range" to "bytes $start-$end/${data.size}"))
            }
        }
        val base get() = URI(origin.base)
        fun spec(id: String = "same", offset: Long = 0, length: Long = -1, key: String? = null) =
            DataSpec.Builder().setUri(origin.base + "/me/sync/files/" + Uri.encode(id))
                .setPosition(offset).setLength(length).setKey(key).build()
        fun factory(transport: DataSource.Factory = NoRedirectAudioDataSource.Factory(base, tls.client),
                    session: AuthSessionManager = auth) = MediaCacheManager.dataSourceFactory(context, cache, session, base, transport)
        suspend fun read(spec: DataSpec = spec(), factory: DataSource.Factory = factory()): String =
            withContext(Dispatchers.IO) { String(readAudio(factory.createDataSource(), spec)) }
        fun switch(user: String, expired: Boolean = false) {
            auth.logout(); auth.saveSession(token(user, expires=if (expired) 1 else 10_000), "synthetic-refresh-$user", user, null)
        }
        suspend fun restart() = withContext(Dispatchers.IO) {
            cache.release(); database.close(); database=StandaloneDatabaseProvider(context)
            cache=SimpleCache(folder,LeastRecentlyUsedCacheEvictor(300L*1024*1024),database)
        }
        override fun close() { try { cache.release() } finally { database.close(); tls.close() } }
    }

    @Test fun sameUriUsesDifferentOwnerBytesAndIgnoresHostileCustomCacheKey() = runBlocking {
        World().use { w ->
            assertEquals("A".repeat(12), w.read())
            val aKey = w.cache.keys.single()
            w.switch("B")
            assertEquals("B".repeat(12), w.read(w.spec(key=aKey)))
            assertEquals(2, w.origin.received.size); assertEquals(2, w.cache.keys.size)
            assertTrue(w.cache.keys.none { it.contains("Bearer") || it.contains(token("A")) || it.contains("synthetic-refresh") })
            w.switch("A")
            assertEquals("A".repeat(12), w.read()); assertEquals(2, w.origin.received.size)
        }
    }
    @Test fun serverDenialForBCannotBeBypassedUsingACache() = runBlocking {
        World().use { w ->
            w.read(); w.denyB=true; w.switch("B")
            val error=runCatching { w.read() }.exceptionOrNull()
            assertTrue(error is HttpDataSource.InvalidResponseCodeException)
            assertEquals(403,(error as HttpDataSource.InvalidResponseCodeException).responseCode)
            assertEquals(2,w.origin.received.size)
            assertEquals("Bearer ${token("B")}",w.origin.received.last().authorization)
        }
    }
    @Test fun logoutRejectsDiskOnlyPrivateReadBeforeAnyNetwork() = runBlocking {
        World().use { w ->
            w.read();w.auth.logout()
            assertTrue(runCatching { w.read() }.exceptionOrNull() is SessionUnavailableException)
            assertEquals(1,w.origin.received.size)
        }
    }
    @Test fun switchBetweenOpenAndReadInvalidatesAWithoutPublishingAnyByte() = runBlocking {
        World().use { w ->
            w.read()
            val source=w.factory().createDataSource()
            withContext(Dispatchers.IO) { source.open(w.spec()) }
            w.switch("B")
            val buffer=ByteArray(12) { 42 }
            try {
                withContext(Dispatchers.IO) { assertTrue(runCatching { source.read(buffer,0,12) }.isFailure) }
                assertTrue(buffer.all { it==42.toByte() });assertEquals(1,w.origin.received.size)
            } finally { source.close() }
        }
    }
    @Test fun switchBeforeMissCannotSendBTokenUnderAKey() = runBlocking {
        World().use { w ->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val native=NoRedirectAudioDataSource.Factory(w.base,w.tls.client)
            val transport=DataSource.Factory {
                entered.complete(Unit);runBlocking { release.await() };native.createDataSource()
            }
            val pending=async(Dispatchers.IO) { runCatching { w.read(factory=w.factory(transport)) } }
            withTimeout(10_000) { entered.await() };w.switch("B");release.complete(Unit)
            assertTrue(withTimeout(10_000) { pending.await() }.isFailure)
            assertTrue(w.origin.received.isEmpty());assertTrue(w.cache.keys.all { w.cache.getCachedSpans(it).isEmpty() })
        }
    }
    @Test fun lateRefreshCallbackCannotPublishOrWriteAsTheNewAccount() = runBlocking {
        World().use { w ->
            w.switch("A",expired=true)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            w.refresh={ entered.complete(Unit);release.await();renewed("A") }
            val pending=async(Dispatchers.IO) { runCatching { w.read() } }
            withTimeout(10_000) { entered.await() };w.switch("B");release.complete(Unit)
            assertTrue(withTimeout(10_000) { pending.await() }.isFailure)
            assertEquals("B",w.store.saved?.userId);assertTrue(w.origin.received.isEmpty())
        }
    }
    @Test fun concurrentMissesShareRefreshAndKeepStableOwnerKeys() = runBlocking {
        World().use { w ->
            w.switch("A",expired=true)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
            w.refresh={ calls.incrementAndGet();entered.complete(Unit);release.await();renewed("A") }
            val a=async(Dispatchers.IO) { w.read(w.spec("one")) }
            val b=async(Dispatchers.IO) { w.read(w.spec("two")) }
            withTimeout(10_000) { entered.await() };release.complete(Unit)
            assertEquals("A".repeat(12),withTimeout(10_000) { a.await() })
            assertEquals("A".repeat(12),withTimeout(10_000) { b.await() });assertEquals(1,calls.get())
            assertEquals(setOf(PrivateStreamCache.key(w.base,"A","one"),PrivateStreamCache.key(w.base,"A","two")),w.cache.keys)
        }
    }
    @Test fun switchDuringNativeReadDoesNotPublishStaleBytesToCaller() = runBlocking {
        World().use { w ->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val native=NoRedirectAudioDataSource.Factory(w.base,w.tls.client)
            val transport=DataSource.Factory {
                val physical=native.createDataSource()
                object : DataSource by physical {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count=physical.read(buffer,offset,length)
                        entered.complete(Unit);runBlocking { release.await() };return count
                    }
                }
            }
            val source=w.factory(transport).createDataSource();val buffer=ByteArray(12) { 42 }
            withContext(Dispatchers.IO) { source.open(w.spec()) }
            val pending=async(Dispatchers.IO) { runCatching { source.read(buffer,0,buffer.size) } }
            withTimeout(10_000) { entered.await() };w.switch("B");release.complete(Unit)
            try {
                assertTrue(withTimeout(10_000) { pending.await() }.isFailure)
                assertTrue(buffer.all { it==42.toByte() })
            } finally { source.close() }
        }
    }
    @Test fun partialRangesAndMultipleSpansRemainOwnerScoped() = runBlocking {
        World().use { w ->
            assertEquals("AAAA",w.read(w.spec(offset=0,length=4)))
            assertEquals("AAAA",w.read(w.spec(offset=8,length=4)))
            assertEquals("A".repeat(12),w.read(w.spec(length=12)))
            assertTrue(w.origin.received.any { it.range=="bytes=4-7" })
            val before=w.origin.received.size
            w.switch("B");assertEquals("B".repeat(12),w.read(w.spec(length=12)))
            w.switch("A");assertEquals("A".repeat(12),w.read(w.spec(length=12)))
            assertEquals(before+1,w.origin.received.size)
        }
    }
    @Test fun diskReadFinishingAfterLogoutCannotPublishIntoCallerBuffer() = runBlocking {
        World().use { w ->
            w.read()
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val disk=DataSource.Factory {
                val physical=FileDataSource()
                object : DataSource by physical {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count=physical.read(buffer,offset,length)
                        entered.complete(Unit);runBlocking { release.await() };return count
                    }
                }
            }
            val source=PrivateCacheDataSource(w.auth,w.base,w.cache,DefaultDataSource.Factory(context),PrivateCacheGenerationStore(context),disk) {
                SessionHttpDataSource(NoRedirectAudioDataSource.Factory(w.base,w.tls.client),w.auth,w.base,it)
            }
            withContext(Dispatchers.IO) { source.open(w.spec()) }
            val buffer=ByteArray(12) { 42 }
            val pending=async(Dispatchers.IO) { runCatching { source.read(buffer,0,buffer.size) } }
            withTimeout(10_000) { entered.await() };w.auth.logout();release.complete(Unit)
            try {
                assertTrue(withTimeout(10_000) { pending.await() }.isFailure)
                assertTrue(buffer.all { it==42.toByte() });assertEquals(1,w.origin.received.size)
            } finally { withContext(Dispatchers.IO) { source.close() } }
        }
    }
    @Test fun accountChangeWhileTlsOpenIsPendingCannotCompleteAMiss() = runBlocking {
        World().use { w ->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val delayed=w.tls.endpoint {
                entered.complete(Unit);runBlocking { release.await() }
                LoopbackAudioFixture.Reply(200,"A-PRIVATE".toByteArray())
            }
            val base=URI(delayed.base)
            val factory=MediaCacheManager.dataSourceFactory(context,w.cache,w.auth,base,
                NoRedirectAudioDataSource.Factory(base,w.tls.client))
            val request=DataSpec.Builder().setUri(delayed.base+"/me/sync/files/open-race").build()
            val pending=async(Dispatchers.IO) { runCatching { readAudio(factory.createDataSource(),request) } }
            withTimeout(10_000) { entered.await() };w.switch("B");release.complete(Unit)
            assertTrue(withTimeout(10_000) { pending.await() }.isFailure)
            assertEquals("Bearer ${token("A")}",delayed.received.single().authorization)
            assertTrue(w.cache.keys.all { w.cache.getCachedSpans(it).isEmpty() })
        }
    }
    @Test fun historicalUriKeysAreNeverReusedOrSilentlyAssignedToB() = runBlocking {
        World().use { w ->
            val legacy=CacheDataSource.Factory().setCache(w.cache).setUpstreamDataSourceFactory {
                SessionHttpDataSource(NoRedirectAudioDataSource.Factory(w.base,w.tls.client),w.auth,w.base)
            }
            w.read(factory=legacy);assertTrue(w.cache.keys.contains(w.spec().uri.toString()))
            w.denyB=true;w.switch("B")
            assertTrue(runCatching { w.read() }.isFailure);assertEquals(2,w.origin.received.size)
            assertTrue(w.cache.keys.contains(w.spec().uri.toString())) // Not purged, simply unreachable.
        }
    }
    @Test fun legitimateOwnerCanReloginAndReadOfflineEvenWithExpiredAccessToken() = runBlocking {
        World().use { w ->
            w.read();val keys=w.cache.keys.toSet();var refreshCalls=0
            w.refresh={ refreshCalls++;Result.failure(java.io.IOException("synthetic offline")) }
            w.switch("A",expired=true)
            assertEquals("A".repeat(12),w.read());assertEquals(keys,w.cache.keys)
            assertEquals(0,refreshCalls);assertEquals(1,w.origin.received.size)
        }
    }
    @Test fun legacyCustomKeyImitatingV2CannotAssignAPrivateBytesToB() = runBlocking {
        World().use { w ->
            val forged=PrivateStreamCache.key(w.base,"B","same")
            val legacy=CacheDataSource.Factory().setCache(w.cache).setUpstreamDataSourceFactory {
                SessionHttpDataSource(NoRedirectAudioDataSource.Factory(w.base,w.tls.client),w.auth,w.base)
            }
            assertEquals("A".repeat(12),w.read(w.spec(key=forged),legacy))
            assertTrue(w.cache.getCachedSpans(forged).isNotEmpty())
            w.switch("B");w.denyB=true
            val error=runCatching { w.read() }.exceptionOrNull()
            assertTrue(error is HttpDataSource.InvalidResponseCodeException)
            assertEquals(403,(error as HttpDataSource.InvalidResponseCodeException).responseCode)
            assertTrue(w.cache.getCachedSpans(forged).isEmpty())
        }
    }
    @Test fun persistedCacheSurvivesRestartOnlyForItsOwner() = runBlocking {
        World().use { w ->
            w.read();val keys=w.cache.keys.toSet()
            withContext(Dispatchers.IO) {
                w.cache.release()
                w.database.close();w.database=StandaloneDatabaseProvider(context)
                w.cache=SimpleCache(w.folder,LeastRecentlyUsedCacheEvictor(300L*1024*1024),w.database)
            }
            val restarted=manager(w.store) { error("No refresh required") }
            assertEquals("A".repeat(12),w.read(factory=w.factory(session=restarted)))
            assertEquals(keys,w.cache.keys);assertEquals(1,w.origin.received.size)
            restarted.saveSession(token("B"),"synthetic","B",null);w.denyB=true
            assertTrue(runCatching { w.read(factory=w.factory(session=restarted)) }.isFailure)
        }
    }
    @Test fun refreshDoesNotInvalidateScopeOrPrivateCacheKey() = runBlocking {
        World().use { w ->
            w.read();val scope=w.auth.captureScope();val keys=w.cache.keys.toSet()
            w.refresh={ renewed("A") };assertTrue(w.auth.refreshSession().isSuccess)
            assertTrue(w.auth.isCurrent(scope));assertEquals(scope,w.auth.sessionScope.value)
            assertEquals("A".repeat(12),w.read());assertEquals(keys,w.cache.keys);assertEquals(1,w.origin.received.size)
        }
    }
    @Test fun reassignEvictsExactTrackAcrossOwnersAndRejectsOldOpenReaders() = runBlocking {
        World().use { w ->
            w.read(w.spec("track:deezer:1"));w.read(w.spec("track:deezer:11"))
            w.switch("B");w.read(w.spec("track:deezer:1"));w.switch("A")
            val otherOrigin=PrivateStreamCache.key(URI("https://other.invalid"),"A","track:deezer:1")
            // A sentinel key for another origin is intentionally left intact by the targeted eviction.
            withContext(Dispatchers.IO) {
                val hole=checkNotNull(w.cache.startReadWriteNonBlocking(otherOrigin,0,1))
                try {
                    val file=w.cache.startFile(otherOrigin,0,1);file.writeBytes(byteArrayOf(9))
                    w.cache.commitFile(file,1)
                } finally { w.cache.releaseHoleSpan(hole) }
            }
            val source=w.factory().createDataSource()
            withContext(Dispatchers.IO) { source.open(w.spec("track:deezer:1")) }
            withContext(Dispatchers.IO) { PrivateStreamCache.evict(w.cache,w.base,"track:deezer:1",PrivateCacheGenerationStore(context)) }
            try { withContext(Dispatchers.IO) { assertTrue(runCatching { source.read(ByteArray(4),0,4) }.isFailure) } }
            finally { source.close() }
            assertTrue(w.cache.getCachedSpans(PrivateStreamCache.key(w.base,"A","track:deezer:1")).isEmpty())
            assertTrue(w.cache.getCachedSpans(PrivateStreamCache.key(w.base,"B","track:deezer:1")).isEmpty())
            assertTrue(w.cache.getCachedSpans(otherOrigin).isNotEmpty())
            assertTrue(w.cache.keys.contains(PrivateStreamCache.key(w.base,"A","track:deezer:11")))
            w.version="NEW";assertEquals("A".repeat(12)+"NEW",w.read(w.spec("track:deezer:1")))
        }
    }
    @Test fun reassignCannotResurrectAnOldWriterFinalFragment() = runBlocking {
        World().use { w ->
            val source=w.factory().createDataSource()
            withContext(Dispatchers.IO) {
                source.open(w.spec());assertEquals(4,source.read(ByteArray(4),0,4))
                PrivateStreamCache.evict(w.cache,w.base,"same",PrivateCacheGenerationStore(context))
                source.close()
            }
            assertTrue(w.cache.keys.none { w.cache.getCachedSpans(it).isNotEmpty() })
            w.version="NEW";assertEquals("A".repeat(12)+"NEW",w.read())
        }
    }
    @Test fun failedEvictionBlocksThatResourceUntilACompleteRetry() = runBlocking {
        World().use { w ->
            w.read();var fail=true
            val failing=object : Cache by w.cache {
                override fun removeResource(key: String) {
                    if (fail) throw java.io.IOException("synthetic cache failure")
                    w.cache.removeResource(key)
                }
            }
            withContext(Dispatchers.IO) { assertTrue(runCatching { PrivateStreamCache.evict(failing,w.base,"same",PrivateCacheGenerationStore(context)) }.isFailure) }
            val sources=MediaCacheManager.dataSourceFactory(context,failing,w.auth,w.base,
                NoRedirectAudioDataSource.Factory(w.base,w.tls.client))
            assertTrue(runCatching { w.read(factory=sources) }.isFailure)
            assertEquals(1,w.origin.received.size)
            fail=false
            withContext(Dispatchers.IO) { PrivateStreamCache.evict(failing,w.base,"same",PrivateCacheGenerationStore(context)) }
            w.version="NEW";assertEquals("A".repeat(12)+"NEW",w.read(factory=sources))
            withContext(Dispatchers.IO) {
                val writer=sources.createDataSource()
                writer.open(w.spec("late-fragment"));writer.read(ByteArray(4),0,4)
                PrivateStreamCache.evict(failing,w.base,"late-fragment",PrivateCacheGenerationStore(context))
                fail=true
                assertTrue(runCatching { writer.close() }.isFailure)
            }
            assertTrue(runCatching { w.read(w.spec("late-fragment"),sources) }.isFailure)
        }
    }
    @Test fun durableLocalFavoriteIsUnchangedAndWorksLoggedOut() = runBlocking {
        World().use { w ->
            val favorite=File(temp.newFolder("durable-favorites"),"favorite.audio").apply { writeText("LOCAL-FAVORITE") }
            val before=favorite.readBytes();w.auth.logout()
            val source=w.factory().createDataSource()
            withContext(Dispatchers.IO) {
                source.open(DataSpec.Builder().setUri(Uri.fromFile(favorite)).build())
                w.auth.saveSession(token("B"),"synthetic","B",null)
                val buffer=ByteArray(14);assertEquals(14,source.read(buffer,0,14));assertEquals("LOCAL-FAVORITE",String(buffer))
                source.close();PrivateStreamCache.evict(w.cache,w.base,"same",PrivateCacheGenerationStore(context))
            }
            assertArrayEquals(before,favorite.readBytes());assertTrue(w.origin.received.isEmpty())
        }
    }

    @Test fun failedPartialEvictionCannotResurrectEitherOwnerAfterRestart() = runBlocking {
        World().use { w ->
            w.read();w.read(w.spec("neighbor"));w.switch("B");w.read();w.switch("A")
            val original=w.cache.keys.filter { it.endsWith("|same") }.toSet()
            var calls=0
            val failing=object : Cache by w.cache {
                override fun removeResource(key: String) {
                    if (++calls==2) throw java.io.IOException("synthetic second removal failure")
                    w.cache.removeResource(key)
                }
            }
            withContext(Dispatchers.IO) {
                assertTrue(runCatching { PrivateStreamCache.evict(failing,w.base,"same",PrivateCacheGenerationStore(context)) }.isFailure)
            }
            assertTrue(original.any { w.cache.getCachedSpans(it).isNotEmpty() })
            val uid=w.cache.uid;w.restart();assertEquals(uid,w.cache.uid)
            w.version="NEW"
            assertEquals("A".repeat(12)+"NEW",w.read())
            w.switch("B");w.denyB=true
            assertTrue(runCatching { w.read() }.exceptionOrNull() is HttpDataSource.InvalidResponseCodeException)
            w.switch("A");val before=w.origin.received.size
            assertEquals("A".repeat(12),w.read(w.spec("neighbor")));assertEquals(before,w.origin.received.size)
        }
    }
    @Test fun lateWriterRemovalFailureCannotResurrectAfterRestart() = runBlocking {
        World().use { w ->
            var fail=false
            val failing=object : Cache by w.cache {
                override fun removeResource(key: String) {
                    if (fail) throw java.io.IOException("synthetic close removal failure")
                    w.cache.removeResource(key)
                }
            }
            val factory=MediaCacheManager.dataSourceFactory(context,failing,w.auth,w.base,
                NoRedirectAudioDataSource.Factory(w.base,w.tls.client))
            withContext(Dispatchers.IO) {
                val writer=factory.createDataSource();writer.open(w.spec());writer.read(ByteArray(4),0,4)
                PrivateStreamCache.evict(failing,w.base,"same",PrivateCacheGenerationStore(context))
                fail=true;assertTrue(runCatching { writer.close() }.isFailure)
            }
            assertTrue(w.cache.keys.any { w.cache.getCachedSpans(it).isNotEmpty() })
            w.restart();w.version="NEW"
            assertEquals("A".repeat(12)+"NEW",w.read())
        }
    }
    @Test fun generationPersistsAcrossMultipleRetriesAndNormalRestarts() = runBlocking {
        World().use { w ->
            w.read();val old=w.cache.keys.single()
            withContext(Dispatchers.IO) { PrivateStreamCache.evict(w.cache,w.base,"same",PrivateCacheGenerationStore(context)) }
            w.version="ONE";assertEquals("A".repeat(12)+"ONE",w.read());val first=w.cache.keys.single()
            assertNotEquals(old,first)
            w.restart();val count=w.origin.received.size
            assertEquals("A".repeat(12)+"ONE",w.read());assertEquals(count,w.origin.received.size)
            withContext(Dispatchers.IO) { PrivateStreamCache.evict(w.cache,w.base,"same",PrivateCacheGenerationStore(context)) }
            w.version="TWO";assertEquals("A".repeat(12)+"TWO",w.read());assertNotEquals(first,w.cache.keys.single())
        }
    }
    @Test fun corruptGenerationFailsClosedAfterRestart() = runBlocking {
        World().use { w ->
            w.read()
            withContext(Dispatchers.IO) { PrivateStreamCache.evict(w.cache,w.base,"same",PrivateCacheGenerationStore(context)) }
            w.version="NEW";w.read()
            val directory=File(context.noBackupFilesDir,"private_stream_generations/${w.cache.uid}")
            directory.listFiles()!!.single().writeText("invalid generation")
            w.restart();val count=w.origin.received.size
            assertTrue(runCatching { w.read() }.exceptionOrNull() is java.io.IOException)
            assertEquals(count,w.origin.received.size)
        }
    }
}
