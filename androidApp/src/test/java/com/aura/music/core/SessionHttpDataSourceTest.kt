package com.aura.music.core

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,manifest=Config.NONE)
class SessionHttpDataSourceTest {
    private fun recordingFactory(specs: MutableList<DataSpec>) = DataSource.Factory { object : DataSource {
        override fun addTransferListener(listener: TransferListener) {}
        override fun open(spec: DataSpec): Long { specs+=spec; return 10 }
        override fun getUri(): Uri? = null
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun close() {}
    } }

    @Test fun mainThreadIsRejectedBeforeAnyNetwork() {
        val auth=manager(MemorySessionStore(StoredSession(token(),"old","A",null))) { error("No refresh") }
        val specs=mutableListOf<DataSpec>()
        val source=SessionHttpDataSource(recordingFactory(specs),auth,URI("https://api.example.invalid"))
        assertTrue(runCatching { source.open(DataSpec.Builder().setUri("https://api.example.invalid/audio").build()) }.isFailure)
        assertTrue(specs.isEmpty())
    }

    @Test fun explicitHttpsDefaultPortRefreshesAndOtherOriginNeverGetsBearer() = runBlocking {
        var refreshes=0
        val auth=manager(MemorySessionStore(StoredSession(token(expires=900),"old","A",null))) { refreshes++; renewed() }
        val specs=mutableListOf<DataSpec>()
        val source=SessionHttpDataSource(recordingFactory(specs),auth,URI("https://api.example.invalid"))
        withContext(Dispatchers.IO) {
            source.open(DataSpec.Builder().setUri("https://api.example.invalid:443/me/sync/files/test").build())
            source.close()
            assertTrue(runCatching { source.open(DataSpec.Builder().setUri("https://external.example.invalid/audio")
                .setHttpRequestHeaders(mapOf("Authorization" to "Bearer synthetic")).build()) }.isFailure)
            source.close()
        }
        assertEquals(1,refreshes)
        assertEquals(auth.getBearerHeader(),specs.first().httpRequestHeaders["Authorization"])
        assertEquals(1,specs.size) // Foreign origin was rejected before invoking the transport.
    }

    @Test fun loaderRefreshes401OnceAndKeepsRangePosition() = runBlocking {
        val auth=manager(MemorySessionStore(StoredSession(token(),"old","A",null))) { renewed() }
        val specs=mutableListOf<DataSpec>()
        val factory=DataSource.Factory { object : DataSource {
            override fun addTransferListener(listener: TransferListener) {}
            override fun open(spec: DataSpec): Long {
                specs+=spec
                if(specs.size==1) throw HttpDataSource.InvalidResponseCodeException(401,"expired",null,emptyMap(),spec,byteArrayOf())
                return 10
            }
            override fun getUri(): Uri? = null
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun close() {}
        } }
        val source=SessionHttpDataSource(factory,auth,URI("https://api.example.invalid"))
        val spec=DataSpec.Builder().setUri("https://api.example.invalid/me/sync/files/test").setPosition(123).build()
        withContext(Dispatchers.IO) { assertEquals(10,source.open(spec).toInt()) }
        assertEquals(2,specs.size); assertEquals(123,specs.last().position.toInt())
        assertEquals(auth.getBearerHeader(),specs.last().httpRequestHeaders["Authorization"])
        source.close()
    }
}
