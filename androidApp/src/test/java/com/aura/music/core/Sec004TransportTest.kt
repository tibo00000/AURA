package com.aura.music.core

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.net.URI
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

internal fun readAudio(source: DataSource, spec: DataSpec): ByteArray {
    try {
        source.open(spec)
        val result = ByteArrayOutputStream(); val buffer = ByteArray(1024)
        while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; result.write(buffer, 0, count) }
        return result.toByteArray()
    } finally { source.close() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], application=Application::class, manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class Sec004TransportTest {
    @get:Rule val temp = TemporaryFolder()
    private fun auth() = manager(MemorySessionStore(StoredSession(token(), "synthetic", "A", null))) { renewed() }
    private fun source(fixture: LoopbackAudioFixture, base: String, auth: AuthSessionManager = auth()) =
        SessionHttpDataSource(NoRedirectAudioDataSource.Factory(URI(base), fixture.client), auth, URI(base))
    private fun spec(url: String) = DataSpec.Builder().setUri(url).build()

    @Test fun httpsPrivateStreamAndRangeHaveOnlyTheCurrentSyntheticBearer() = runBlocking {
        LoopbackAudioFixture().use { fixture ->
            val endpoint = fixture.endpoint { LoopbackAudioFixture.Reply(206, "3456".toByteArray(), mapOf("Content-Range" to "bytes 3-6/10")) }
            val auth = auth()
            val request = DataSpec.Builder().setUri(endpoint.base + "/me/sync/files/track%3Adeezer%3A1")
                .setPosition(3).setLength(4).setHttpRequestHeaders(mapOf("authorization" to "forged-synthetic")).build()
            val data = withContext(Dispatchers.IO) { readAudio(source(fixture, endpoint.base, auth), request) }
            assertEquals("3456", String(data)); assertEquals("bytes=3-6", endpoint.received.single().range)
            assertEquals(auth.getBearerHeader(), endpoint.received.single().authorization)
        }
    }

    @Test fun attackerAndWrongPathsFailBeforeAnyRequest() = runBlocking {
        LoopbackAudioFixture().use { fixture ->
            val allowed = fixture.endpoint(); val attacker = fixture.endpoint()
            val plain = fixture.endpoint(secure=false)
            val urls = listOf(attacker.base + "/me/sync/files/1", plain.base + "/me/sync/files/1",
                allowed.base + "/health", allowed.base + "/me/sync/files/../health",
                allowed.base + "/me/sync/files/%2Fhealth", allowed.base + "/me/sync/files/%252Fhealth",
                allowed.base + "/me/sync/files/1?token=synthetic", allowed.base + "/me/sync/files/1#fragment")
            withContext(Dispatchers.IO) { urls.forEach {
                assertTrue(runCatching { readAudio(source(fixture, allowed.base), spec(it)) }.isFailure)
            } }
            assertTrue(allowed.received.isEmpty()); assertTrue(attacker.received.isEmpty()); assertTrue(plain.received.isEmpty())
        }
    }

    @Test fun redirectsNeverReachAnotherHttpsOriginOrPlainHttpEvenIfSameHost() = runBlocking {
        LoopbackAudioFixture().use { fixture ->
            val secureTarget = fixture.endpoint(); val plainTarget = fixture.endpoint(secure=false)
            for (target in listOf(secureTarget, plainTarget)) {
                val origin = fixture.endpoint { LoopbackAudioFixture.Reply(302, byteArrayOf(), mapOf("Location" to target.base + "/capture")) }
                val auth = auth()
                val failure = withContext(Dispatchers.IO) {
                    runCatching { readAudio(source(fixture, origin.base, auth), spec(origin.base + "/me/sync/files/1")) }.exceptionOrNull()
                }
                assertNotNull(failure); assertFalse(failure.toString().contains(auth.getBearerHeader()))
                assertEquals(1, origin.received.size); assertEquals(auth.getBearerHeader(), origin.received.single().authorization)
                assertTrue(target.received.isEmpty())
            }
        }
    }

    @Test fun transportItselfRejectsWrongOriginsWithoutSessionWrapper() = runBlocking {
        LoopbackAudioFixture().use { fixture ->
            val origin = fixture.endpoint(); val target = fixture.endpoint()
            withContext(Dispatchers.IO) {
                val transport = NoRedirectAudioDataSource.Factory(URI(origin.base), fixture.client).createDataSource()
                assertTrue(runCatching { readAudio(transport, DataSpec.Builder().setUri(target.base + "/capture")
                    .setHttpRequestHeaders(mapOf("Authorization" to "Bearer synthetic")).build()) }.isFailure)
            }
            assertTrue(target.received.isEmpty())
        }
    }

    @Test fun sameOriginRedirectIsRejectedAndErrorsAndAndroidLogsContainNoBearer() = runBlocking {
        LoopbackAudioFixture().use { fixture ->
            val auth = auth()
            lateinit var origin: LoopbackAudioFixture.Endpoint
            origin = fixture.endpoint { LoopbackAudioFixture.Reply(307, auth.getBearerHeader().toByteArray(),
                mapOf("Location" to origin.base + "/me/sync/files/2")) }
            val error = withContext(Dispatchers.IO) {
                runCatching { readAudio(source(fixture, origin.base, auth), spec(origin.base + "/me/sync/files/1")) }.exceptionOrNull()
            }
            assertNotNull(error); assertEquals(1, origin.received.size)
            assertFalse(error.toString().contains(auth.getBearerHeader()))
            assertTrue(org.robolectric.shadows.ShadowLog.getLogs().none { it.msg.contains(auth.getBearerHeader()) })
        }
    }

    @Test fun real401RetriesOnceWithRefreshedToken() = runBlocking {
        LoopbackAudioFixture().use { fixture ->
            val requests = AtomicInteger()
            val origin = fixture.endpoint { LoopbackAudioFixture.Reply(if (requests.incrementAndGet() == 1) 401 else 200) }
            val auth = auth()
            withContext(Dispatchers.IO) { readAudio(source(fixture, origin.base, auth), spec(origin.base + "/me/sync/files/1")) }
            assertEquals(2, requests.get()); assertNotEquals(origin.received.first().authorization, origin.received.last().authorization)
            assertEquals(auth.getBearerHeader(), origin.received.last().authorization)
        }
    }

    @Test fun realCacheCanReadAlreadyFetchedAudioWithoutNetwork() = runBlocking {
        LoopbackAudioFixture().use { fixture ->
            val origin = fixture.endpoint()
            val context = RuntimeEnvironment.getApplication()
            val database = StandaloneDatabaseProvider(context)
            val cache = SimpleCache(File(temp.root, "stream-cache"), NoOpCacheEvictor(), database)
            try {
                val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory {
                    source(fixture, origin.base)
                }
                val request = spec(origin.base + "/me/sync/files/1")
                val expected = withContext(Dispatchers.IO) { readAudio(factory.createDataSource(), request) }
                val offline = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null)
                val actual = withContext(Dispatchers.IO) { readAudio(offline.createDataSource(), request) }
                assertArrayEquals(expected, actual); assertEquals(1, origin.received.size)
            } finally { cache.release(); database.close() }
        }
    }

    @Test fun localFileStillUsesDefaultDataSourceWithoutAuthOrNetwork() = runBlocking {
        val local = temp.newFile("offline.mp3").apply { writeText("local synthetic audio") }
        val context = RuntimeEnvironment.getApplication()
        val factory = DefaultDataSource.Factory(context, DataSource.Factory { object : DataSource {
            override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {}
            override fun open(spec: DataSpec): Long = error("HTTP must not be opened for a local source")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("HTTP read forbidden")
            override fun getUri(): Uri? = null
            override fun close() {}
        } })
        val data = withContext(Dispatchers.IO) { readAudio(factory.createDataSource(), spec(Uri.fromFile(local).toString())) }
        assertEquals("local synthetic audio", String(data))
    }
}
