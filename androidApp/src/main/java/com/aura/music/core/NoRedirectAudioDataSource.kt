package com.aura.music.core

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/** The physical transport never follows Location, irrespective of the controller or wrapper. */
internal class NoRedirectAudioDataSource private constructor(
    private val client: OkHttpClient,
    private val policy: PrivateStreamPolicy
) : BaseDataSource(true) {
    private var response: Response? = null
    private var call: Call? = null
    private var input: InputStream? = null
    private var spec: DataSpec? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("Lecture HTTP hors thread de travail")
        val allowed = runCatching { policy.allows(URI(dataSpec.uri.toString())) }.getOrDefault(false)
        if (!allowed || dataSpec.httpMethod !in setOf(DataSpec.HTTP_METHOD_GET, DataSpec.HTTP_METHOD_HEAD)) {
            throw IOException("Source audio non autorisée")
        }
        transferInitializing(dataSpec)
        spec = dataSpec
        try {
            val request = Request.Builder().url(dataSpec.uri.toString())
                .method(if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_HEAD) "HEAD" else "GET", null)
            dataSpec.httpRequestHeaders.forEach { (name, value) -> request.header(name, value) }
            request.header("Accept-Encoding", "identity")
            if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else
                    (dataSpec.position + dataSpec.length - 1).toString()
                request.header("Range", "bytes=${dataSpec.position}-$end")
            }
            val nextCall = client.newCall(request.build()).also { call = it }
            val result = nextCall.execute().also { response = it }
            if (result.code !in 200..299) {
                // Do not retain an error body which could reflect a credential.
                throw HttpDataSource.InvalidResponseCodeException(result.code, null, null,
                    result.headers.toMultimap(), dataSpec, byteArrayOf())
            }
            val body = result.body ?: throw IOException("Réponse audio vide")
            input = body.byteStream()
            val skip = if (result.code == 200) dataSpec.position else 0L
            remaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                body.contentLength() >= 0 -> (body.contentLength() - skip).coerceAtLeast(0)
                else -> C.LENGTH_UNSET.toLong()
            }
            opened = true
            transferStarted(dataSpec)
            var pending = skip
            val discard = ByteArray(4096)
            while (pending > 0) {
                val read = input!!.read(discard, 0, minOf(pending, discard.size.toLong()).toInt())
                if (read < 0) throw IOException("Position audio indisponible")
                pending -= read
                bytesTransferred(read)
            }
            return remaining
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            close()
            throw e
        } catch (_: Exception) {
            close()
            throw IOException("Lecture HTTP indisponible")
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = if (remaining < 0) length else minOf(remaining, length.toLong()).toInt()
        val read = try { input?.read(buffer, offset, count) ?: C.RESULT_END_OF_INPUT }
            catch (_: IOException) { throw IOException("Lecture audio interrompue") }
        if (read > 0) {
            if (remaining >= 0) remaining -= read
            bytesTransferred(read)
        } else if (read < 0 && remaining > 0) throw IOException("Réponse audio incomplète")
        return read
    }

    override fun getUri(): Uri? = spec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap().orEmpty()
    override fun close() {
        try { response?.close() } finally {
            response = null; input = null; spec = null
            call?.cancel(); call = null
            if (opened) { opened = false; transferEnded() }
        }
    }

    class Factory(base: URI, client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()) : DataSource.Factory {
        private val policy = PrivateStreamPolicy(base)
        private val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        override fun createDataSource(): DataSource = NoRedirectAudioDataSource(transport, policy)
    }
}
