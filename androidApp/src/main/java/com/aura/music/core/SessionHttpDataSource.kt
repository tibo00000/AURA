package com.aura.music.core

import android.os.Looper
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** open() is called by Media3's loader, never by the UI thread. Only reads can retry. */
internal class SessionHttpDataSource(
    private val factory: DataSource.Factory,
    private val auth: AuthSessionManager,
    private val base: URI,
    private val expectedScope: AuthSessionManager.Scope? = null
) : DataSource {
    private var delegate: DataSource = factory.createDataSource()
    private var activeLease: AuthSessionManager.Lease? = null
    private val listeners = mutableListOf<TransferListener>()
    private val policy = PrivateStreamPolicy(base)
    override fun addTransferListener(listener: TransferListener) { listeners.add(listener); delegate.addTransferListener(listener) }
    override fun open(spec: DataSpec): Long {
        activeLease = null
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("Le chargement audio exige un thread de travail.")
        if (!runCatching { policy.allows(URI(spec.uri.toString())) }.getOrDefault(false) ||
            spec.httpMethod !in setOf(DataSpec.HTTP_METHOD_GET, DataSpec.HTTP_METHOD_HEAD)) {
            throw IOException("Source audio non autorisée")
        }
        fun authorized(value: String) = spec.buildUpon().setHttpRequestHeaders(
            spec.httpRequestHeaders.filterKeys { !it.equals("Authorization", true) } +
                ("Authorization" to value)).build()
        val captured = expectedScope ?: auth.captureScope()
        auth.requireCurrent(captured)
        val lease = runBlocking(Dispatchers.IO) { auth.validSession(scope = captured) }
        auth.requireCurrent(captured)
        if (lease.userId != captured.userId || lease.epoch != captured.epoch) throw SessionUnavailableException()
        activeLease = lease
        try {
            val length = delegate.open(authorized(lease.bearer))
            if (!auth.isCurrent(lease)) { delegate.close(); throw SessionUnavailableException() }
            return length
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode != 401) throw e
            delegate.close()
            val renewed = runBlocking(Dispatchers.IO) { auth.validSession(lease, captured) }
            activeLease = renewed
            if (!auth.isCurrent(renewed)) throw SessionUnavailableException()
            delegate = factory.createDataSource().also { next -> listeners.forEach(next::addTransferListener) }
            val length = delegate.open(authorized(renewed.bearer))
            if (!auth.isCurrent(renewed)) { delegate.close(); throw SessionUnavailableException() }
            return length
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (activeLease?.let { !auth.isCurrent(it) } == true) throw SessionUnavailableException()
        val count = delegate.read(buffer, offset, length)
        if (activeLease?.let { !auth.isCurrent(it) } == true) throw SessionUnavailableException()
        return count
    }
    override fun getUri() = delegate.uri
    override fun getResponseHeaders() = delegate.responseHeaders
    override fun close() = delegate.close()
}
