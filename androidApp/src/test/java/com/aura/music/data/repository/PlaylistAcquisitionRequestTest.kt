package com.aura.music.data.repository

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.aura.music.core.AuthSessionManager
import com.aura.music.core.MemorySessionStore
import com.aura.music.core.StoredSession
import com.aura.music.core.token
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.AuraDatabaseConstructor
import com.aura.music.data.local.PlaylistEntity
import com.aura.music.data.local.PlaylistSyncStateEntity
import com.aura.music.data.local.SyncOutboxEntity
import com.aura.music.data.local.UserSettingsEntity
import com.aura.music.data.media.MediaStoreAudioDataSource
import com.aura.music.data.network.AuraResponse
import com.aura.music.data.network.BootstrapResponseDto
import com.aura.music.data.network.KtorAuraApiService
import com.aura.music.data.network.PlaylistAcquisitionStatus
import com.aura.music.data.network.PlaylistSnapshotEntry
import com.aura.music.data.network.PlaylistSnapshotResponse
import com.aura.music.data.network.PushBatchRequestDto
import com.aura.music.data.network.PushBatchResponseDto
import com.aura.music.data.network.SyncOperationResultDto
import com.aura.music.data.network.SyncTokenDto
import com.aura.music.data.playlist.PlaylistSourceReadException
import com.aura.music.data.playlist.PlaylistSourceReader
import com.aura.music.data.playlist.PlaylistSourceRepository
import com.aura.music.domain.player.PlaylistPlayback
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlaylistAcquisitionRequestTest {
    private val stamp = "2026-11-16T22:00:00Z"
    private val auth = AuthSessionManager(
        MemorySessionStore(StoredSession(token("A"), "synthetic-refresh", "A", null)),
        { _, _ -> error("Unused") }, { error("Unused") }, { 1000L },
    )
    private lateinit var db: AuraDatabase
    private lateinit var client: HttpClient
    private val calls = mutableListOf<Pair<HttpMethod, String>>()
    private val acquisitions = listOf(PlaylistAcquisitionStatus("remaining", "pending", "missing"))

    @Before
    fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder<AuraDatabase>(
            context = RuntimeEnvironment.getApplication(),
            factory = { AuraDatabaseConstructor.initialize() },
        ).setDriver(AndroidSQLiteDriver()).build()
        db.playlistDao().insertPlaylist(PlaylistEntity("mix", "Mix", isPinned = false, createdAt = 1, updatedAt = 1))
        db.playlistDao().upsertPlaylistSyncState(PlaylistSyncStateEntity("mix", "A", stamp, "ord_fixture", 1))
    }

    @After
    fun tearDown() {
        client.close()
        db.close()
    }

    private fun repository(readReport: () -> List<PlaylistAcquisitionStatus>? = { acquisitions }): PlaylistSourceRepository {
        client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            calls.add(request.method to path)
            val body = when (path) {
                "/me/sync/push-batch" -> {
                    val batch = Json.decodeFromString<PushBatchRequestDto>(
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
                    )
                    val deletion = batch.operations.single()
                    assertEquals("removed-occurrence", deletion.entityId)
                    val result = SyncOperationResultDto(
                        deletion.operationId, deletion.entityType, deletion.entityId, "applied", stamp, playlistRevision = 2,
                    )
                    Json.encodeToString(AuraResponse(data = PushBatchResponseDto(batch.batchId, listOf(result), SyncTokenDto("st_test", stamp))))
                }
                "/me/playlists/snapshot" -> {
                    val entry = PlaylistSnapshotEntry(
                        "mix", "A", "Mix", isPinned = false, createdAt = stamp, updatedAt = stamp,
                        orderToken = "ord_fixture", items = emptyList(), itemCount = 0,
                    )
                    Json.encodeToString(AuraResponse(data = PlaylistSnapshotResponse("A", 1, 0, listOf(entry), 2, 1)))
                }
                "/me/sync/bootstrap" -> Json.encodeToString(AuraResponse(data = BootstrapResponseDto(SyncTokenDto("st_test", stamp), JsonObject(emptyMap()))))
                "/me/playlists/mix/acquisitions" -> {
                    val report = if (request.method == HttpMethod.Get) readReport() else acquisitions
                    if (report == null) {
                        return@MockEngine respond("{}", status = HttpStatusCode.ServiceUnavailable,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    Json.encodeToString(AuraResponse(data = report))
                }
                else -> error("Unexpected endpoint $path")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val api = KtorAuraApiService(client, "https://api.example.invalid")
        return PlaylistSourceRepository(
            db, api, auth, SyncRepository(db, api, RuntimeEnvironment.getApplication(), auth),
            PlaylistSourceReader({ error("No provider call expected") }),
        )
    }

    private suspend fun queueRemoval(playlist: String = "mix", owner: String = "A") {
        db.syncOutboxDao().insert(SyncOutboxEntity(
            "remove", "playlist_item", "removed-occurrence", "delete", """{"playlist_id":"$playlist"}""",
            "pending", createdAt = 1, updatedAt = 1, userId = owner,
        ))
    }

    @Test
    fun sendsPendingRemovalBeforeAskingTheServerToAcquireCurrentEntries() = runBlocking {
        db.userSettingsDao().insertOrReplace(UserSettingsEntity("default", true, true, "any", "any"))
        queueRemoval()
        assertEquals(acquisitions, repository().requestAcquisitions("mix", "device"))
        assertEquals(
            listOf("/me/sync/push-batch", "/me/playlists/snapshot", "/me/sync/bootstrap", "/me/playlists/mix/acquisitions"),
            calls.map { it.second },
        )
        assertTrue(db.syncOutboxDao().getUnresolvedPlaylistOperations().isEmpty())
    }

    @Test
    fun leavesCloudUnchangedWhenTheRemovalCannotBeSynced() = runBlocking {
        queueRemoval()
        val repo = repository()
        assertTrue(runCatching { repo.requestAcquisitions("mix", "device", retryTrackId = "removed-track") }.exceptionOrNull() is PlaylistSourceReadException)
        assertEquals(acquisitions, repo.getAcquisitions("mix"))
        assertEquals(listOf(HttpMethod.Get to "/me/playlists/mix/acquisitions"), calls)
    }

    @Test
    fun confirmedPlaylistDoesNotSyncForUnrelatedEdits() = runBlocking {
        queueRemoval(playlist = "another-playlist")
        assertEquals(acquisitions, repository().requestAcquisitions("mix", "device"))
        assertEquals(listOf(HttpMethod.Post to "/me/playlists/mix/acquisitions"), calls)
    }

    @Test
    fun loggedOutSessionCannotStartTheCloudRequest() = runBlocking {
        val repo = repository()
        val owner = auth.captureScope()
        auth.logout()
        val result = runCatching { repo.requestAcquisitions("mix", "device", scope = owner) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun failedReportReadKeepsConfirmedAudioAndRetryOnlyReads() = runBlocking {
        val available = listOf(acquisitions.single().copy(availability = "available"))
        val responses = listOf(available, null, emptyList()).iterator()
        val repo = repository { responses.next() }
        val worker = Job(coroutineContext[Job])
        try {
            val playback = reportPlayback(repo, CoroutineScope(coroutineContext + worker))
            playback.verifyLater("mix")
            withTimeout(5_000) { playback.statuses.first { it["mix"] == available } }

            playback.verifyLater("mix")
            withTimeout(5_000) { playback.verificationFailures.first { "mix" in it } }
            assertEquals(available, playback.statuses.value["mix"])

            playback.verifyLater("mix")
            withTimeout(5_000) { playback.verificationFailures.first { it.isEmpty() } }
            assertEquals(emptyList<PlaylistAcquisitionStatus>(), playback.statuses.value["mix"])
            assertEquals(List(3) { HttpMethod.Get to "/me/playlists/mix/acquisitions" }, calls)
        } finally {
            worker.cancel()
        }
    }

    @Test
    fun failedFirstReportReadHasNoConfirmedDataAndClearsOnLogout() = runBlocking {
        val repo = repository { null }
        val worker = Job(coroutineContext[Job])
        try {
            val playback = reportPlayback(repo, CoroutineScope(coroutineContext + worker))
            playback.verifyLater("mix")
            withTimeout(5_000) { playback.verificationFailures.first { "mix" in it } }
            assertTrue(playback.statuses.value.isEmpty())

            auth.logout()
            withTimeout(5_000) { playback.verificationFailures.first { it.isEmpty() } }
            playback.verifyLater("mix")
            assertEquals(listOf(HttpMethod.Get to "/me/playlists/mix/acquisitions"), calls)
        } finally {
            worker.cancel()
        }
    }

    private fun reportPlayback(repo: PlaylistSourceRepository, worker: CoroutineScope) = PlaylistPlayback(
        auth, repo, worker, { emptySet() }, { error("Only reading the cloud report") },
        MediaStoreAudioDataSource(RuntimeEnvironment.getApplication()),
    )
}
