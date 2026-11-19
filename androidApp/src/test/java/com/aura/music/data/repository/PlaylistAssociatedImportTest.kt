package com.aura.music.data.repository

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.Configuration
import androidx.work.WorkManager
import com.aura.music.core.MemorySessionStore
import com.aura.music.core.StoredSession
import com.aura.music.core.manager
import com.aura.music.core.token
import com.aura.music.data.local.*
import com.aura.music.data.media.MediaStoreAudioDataSource
import com.aura.music.data.network.*
import com.aura.music.data.playlist.*
import com.aura.music.data.spotify.SpotifyAuthManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
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
class PlaylistAssociatedImportTest {
    private val stamp = "2026-11-18T12:00:00Z"
    private val auth = manager(MemorySessionStore(StoredSession(token("A"), null, "A", null))) { error("Unexpected auth refresh") }
    private lateinit var db: AuraDatabase
    private lateinit var client: HttpClient
    private lateinit var importer: PlaylistImportExportManager
    private lateinit var library: LocalLibraryRepository
    private lateinit var source: PlaylistSourceRepository
    private val paths = mutableListOf<String>()
    private var playlistId: String? = null
    private var revision = 0L
    private var sourceRevision = 0L
    private var items = emptyList<PlaylistItemResponse>()
    private var lastRefresh: PlaylistRefreshResult? = null
    private var rejectSource = false
    private var expectConfirmation = false
    private var logoutAfterSource = false
    private val identity = PlaylistRecordingIdentity("deezer", "789")
    private val report get() = ImportReport("Import fixture", "Deezer", 1, 0, 1,
        listOf(MatchedTrack(RawImportTrack("Title", "Artist", provider = "deezer", providerId = "789"))),
        sourceReference = PlaylistSourceReference("deezer", "55"))

    @Before fun setup() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // Mutations still enqueue work normally; this fixture drives all synchronization explicitly.
        if (runCatching { WorkManager.getInstance(context) }.isFailure) {
            val paused = Executor { }
            WorkManager.initialize(context, Configuration.Builder().setExecutor(paused).setTaskExecutor(paused).build())
        }
        db = Room.inMemoryDatabaseBuilder<AuraDatabase>(context, factory = { AuraDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver()).build()
        db.userSettingsDao().insertOrReplace(UserSettingsEntity("default", true, true, "any", "any"))
        client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            paths.add(path)
            fun body() = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val response = when {
                path == "/me/sync/push-batch" -> {
                    val batch = Json.decodeFromString<PushBatchRequestDto>(body())
                    val results = batch.operations.map { op ->
                        assertEquals("playlist", op.entityType)
                        playlistId = op.entityId
                        SyncOperationResultDto(op.operationId, op.entityType, op.entityId, "applied", stamp, playlistRevision = ++revision)
                    }
                    Json.encodeToString(AuraResponse(data = PushBatchResponseDto(batch.batchId, results, SyncTokenDto("st_fixture", stamp))))
                }
                path == "/me/playlists/snapshot" -> {
                    val entry = PlaylistSnapshotEntry(requireNotNull(playlistId), "A", "Import fixture", isPinned = false,
                        createdAt = stamp, updatedAt = stamp, orderToken = "ord_fixture", items = items, itemCount = items.size,
                        sourceProvider = "deezer".takeIf { sourceRevision > 0 }, sourceExternalId = "55".takeIf { sourceRevision > 0 },
                        sourceRevision = sourceRevision, lastRefresh = lastRefresh)
                    Json.encodeToString(AuraResponse(data = PlaylistSnapshotResponse("A", 1, items.size, listOf(entry), revision, 1)))
                }
                path == "/me/sync/files" -> Json.encodeToString(AuraResponse(data = SyncedFileListResponseData()))
                path == "/me/sync/bootstrap" -> Json.encodeToString(AuraResponse(data = BootstrapResponseDto(SyncTokenDto("st_fixture", stamp), JsonObject(emptyMap()))))
                path == "/me/sync/pull-batch" -> Json.encodeToString(AuraResponse(data = PullBatchResponseDto(emptyList(), SyncTokenDto("st_fixture", stamp), false)))
                path.endsWith("/source") -> {
                    if (rejectSource) return@MockEngine respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                    val source = Json.decodeFromString<PlaylistSourceRequest>(body())
                    assertEquals("deezer", source.provider)
                    assertEquals("55", source.externalId)
                    sourceRevision = 1
                    revision++
                    if (logoutAfterSource) auth.logout()
                    Json.encodeToString(AuraResponse(data = PlaylistSourceResponse("deezer", "55", sourceRevision)))
                }
                path.endsWith("/refresh") -> {
                    val refresh = Json.decodeFromString<PlaylistRefreshRequest>(body())
                    assertEquals("789", refresh.tracks.single().providerId)
                    assertEquals(expectConfirmation, refresh.tracks.single().requiresConfirmation)
                    items = listOf(PlaylistItemResponse("source-occurrence", requireNotNull(playlistId), identity.canonicalId, 0, stamp,
                        sourceMetadata = refresh.tracks.single()))
                    lastRefresh = PlaylistRefreshResult(refresh.requestId, sourceRevision, stamp, 1, 0, 0)
                    revision++
                    Json.encodeToString(AuraResponse(data = lastRefresh))
                }
                else -> error("No download or unrelated endpoint expected: $path")
            }
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }
        val api = KtorAuraApiService(client, "https://api.example.invalid")
        val sync = SyncRepository(db, api, context, auth)
        val sourceReader = PlaylistSourceReader({ error("Spotify must not be used") }) { _, _ ->
            JSONObject("""{"total":1,"next":null,"data":[{"id":789,"title":"Title","artist":{"name":"Artist"},"duration":185}]}""")
        }
        source = PlaylistSourceRepository(db, api, auth, sync, sourceReader)
        library = LocalLibraryRepository(db, MediaStoreAudioDataSource(context), { sync }, apiService = api, context = context)
        importer = PlaylistImportExportManager(context, library, SpotifyAuthManager(context), DownloadRepository(db, api, context) { auth }, source, sourceReader, auth)
    }

    @After fun close() { if (::client.isInitialized) client.close(); if (::db.isInitialized) db.close() }

    @Test fun sourceImportKeepsTheReferenceAndTrackIdentityWithoutStartingLegacyDownloads() = runBlocking {
        val id = importer.commitImport(report)
        val state = requireNotNull(db.playlistDao().getPlaylistSyncState(id))
        assertEquals("deezer", state.sourceProvider)
        assertEquals("55", state.sourceExternalId)
        assertEquals(identity.canonicalId, db.playlistDao().getPlaylistTracks(id).single().trackId)
        assertEquals("Title", db.playlistDao().getPlaylistTracks(id).single().title)
        assertTrue(db.syncOutboxDao().getUnresolvedPlaylistOperations().isEmpty())
        assertTrue(paths.indexOfFirst { it.endsWith("/source") } < paths.indexOfFirst { it.endsWith("/refresh") })
    }

    @Test fun importSummarySeparatesProofFromPossibleCopiesWithoutMergingMetadata() = runBlocking {
        for ((id, title) in listOf("track:deezer:789" to "Known", "local:1" to "Possible", "local:2" to "Possible")) {
            db.trackDao().upsertTrack(TrackEntity(id, null, null, title, title.lowercase(), "Artist",
                canonicalAudioSourceType = "local", isLiked = false, isDownloadedByAura = false, createdAt = 1, updatedAt = 1))
        }
        val inputs = listOf(
            RawImportTrack("Known", "Artist", provider = "deezer", providerId = "789"),
            RawImportTrack("Possible", "Artist", provider = "deezer", providerId = "790"),
            RawImportTrack("New", "Artist", provider = "deezer", providerId = "791"),
        )
        val matches = importer.reconcileTracksWithProgress(inputs) { }
        val summary = ImportReport("Fixture", "Deezer", 3, matches.count { it.isMatchedLocally },
            matches.count { !it.isMatchedLocally }, matches)
        assertEquals(1, summary.matchedLocalCount)
        assertEquals(1, summary.possibleMatchCount)
        assertEquals(1, summary.unmatchedCount)
        assertEquals(2, matches[1].possibleLibraryMatchCount)
        assertNull(matches[1].localTrackId)
        assertFalse(matches[1].isMatchedLocally)
        assertEquals("track:deezer:789", matches[0].localTrackId)
        assertTrue(paths.isEmpty())
    }

    @Test fun sourceLabelsFollowRoomChangesWithoutNetworkAndStayScopedToTheAccount() = runBlocking {
        val dao = db.playlistDao()
        dao.insertPlaylist(PlaylistEntity("owned", "Owned", isPinned = false, createdAt = 1, updatedAt = 1))
        dao.insertPlaylist(PlaylistEntity("other", "Other", isPinned = false, createdAt = 1, updatedAt = 1))
        val observed = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val collector = launch(Dispatchers.Default) { library.observePlaylistSourceProviders("A").collect { observed.add(it) } }
        suspend fun awaitMap(expected: Map<String, String>) = withTimeout(5_000) {
            while (observed.lastOrNull() != expected) delay(10)
        }
        try {
            withTimeout(5_000) { while (observed.isEmpty()) delay(10) }
            val owned = PlaylistSyncStateEntity("owned", "A", stamp, "order", 1, "spotify", "source", 1)
            dao.upsertPlaylistSyncState(owned)
            awaitMap(mapOf("owned" to "spotify"))
            dao.upsertPlaylistSyncState(PlaylistSyncStateEntity("other", "B", stamp, "order", 1, "deezer", "55", 1))
            dao.upsertPlaylistSyncState(owned.copy(lastRefreshJson = "{}"))
            dao.upsertPlaylistSyncState(owned.copy(sourceProvider = "deezer", sourceExternalId = "55", sourceRevision = 2))
            awaitMap(mapOf("owned" to "deezer"))
            dao.upsertPlaylistSyncState(owned.copy(sourceProvider = null, sourceExternalId = null, sourceRevision = 3))
            awaitMap(emptyMap())
            assertEquals(listOf(emptyMap(), mapOf("owned" to "spotify"), mapOf("owned" to "deezer"), emptyMap()), observed.toList())
            assertTrue(paths.isEmpty())
        } finally { collector.cancel() }
    }

    @Test fun interruptedAssociationKeepsOneCreatedPlaylistAndReportsItsIdentity() = runBlocking {
        rejectSource = true
        val failure = runCatching { importer.commitImport(report) }.exceptionOrNull()
        assertTrue(failure is PlaylistImportCreatedException)
        assertEquals(playlistId, (failure as PlaylistImportCreatedException).playlistId)
        assertNotNull(db.playlistDao().getPlaylistEntity(failure.playlistId))
        assertFalse(paths.any { it.endsWith("/refresh") })
    }

    @Test fun knownCatalogueAliasReusesTheLocalAudioWithoutAConfirmation() = runBlocking {
        val alias = "track:deezer:789"
        db.trackDao().upsertTrack(TrackEntity(alias, null, null, "Title", "title", "Artist",
            canonicalAudioSourceType = "local", isLiked = true, isDownloadedByAura = false, createdAt = 1, updatedAt = 1))
        db.trackDao().upsertTrackMediaLinks(listOf(TrackMediaLinkEntity("alias-media", alias, 42, "content://media/42",
            availabilityStatus = "available", lastScannedAt = 1)))
        val id = importer.commitImport(report)
        val rows = db.playlistDao().getPlaylistTracks(id)
        val ready = source.resolveKnownLocalAudio(rows).single()
        assertEquals("content://media/42", ready.contentUri)
        assertEquals("source-occurrence", ready.playlistItemId)
        assertEquals(identity.canonicalId, ready.trackId)
        assertEquals("Title", db.trackDao().getTrackById(alias)?.title)
        assertTrue(requireNotNull(lastRefresh).uncertain.isEmpty())
    }

    @Test fun unavailableImportedReferenceDoesNotHideAPlayableDifferentCopy() = runBlocking {
        val id = importer.commitImport(report)
        val localId="track:local:separate"
        db.trackDao().upsertTrack(TrackEntity(localId,null,null,"Title","title","Artist, Guest",
            canonicalAudioSourceType="local",isLiked=false,isDownloadedByAura=false,createdAt=1,updatedAt=1))
        db.trackDao().upsertTrackMediaLinks(listOf(TrackMediaLinkEntity("local-media",localId,43,"content://media/43",
            availabilityStatus="available",lastScannedAt=1)))
        expectConfirmation=true
        source.refresh(id,"fixture-device")
        assertEquals(1,db.playlistDao().getPlaylistTracks(id).size)
    }

    @Test fun disabledSyncPreventsCreatingAnEmptyPlaylist() = runBlocking {
        db.userSettingsDao().insertOrReplace(UserSettingsEntity("default", false, true, "any", "any"))
        assertTrue(runCatching { importer.commitImport(report) }.exceptionOrNull() is PlaylistSourceReadException)
        assertTrue(db.syncOutboxDao().getUnresolvedPlaylistOperations().isEmpty())
        assertTrue(paths.isEmpty())
    }

    @Test fun logoutDuringAssociationStopsBeforeRefreshAndDoesNotPublishTheResponse() = runBlocking {
        logoutAfterSource = true
        assertTrue(runCatching { importer.commitImport(report) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(paths.any { it.endsWith("/refresh") })
        assertNull(db.playlistDao().getPlaylistSyncState(requireNotNull(playlistId))?.sourceProvider)
    }

    @Test fun lostScopeRollsBackPlaylistCreationTogetherWithItsOutboxEntry() = runBlocking {
        var checks = 0
        val failure = runCatching { library.createPlaylist("Rollback fixture") { ++checks < 2 } }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(library.getPlaylists().isEmpty())
        assertTrue(db.syncOutboxDao().getUnresolvedPlaylistOperations().isEmpty())
    }

    @Test fun lostScopeRollsBackAnAddedCopyWithoutChangingThePlaylist() = runBlocking {
        db.playlistDao().insertPlaylist(PlaylistEntity("mix", "Mix", isPinned = false, createdAt = 1, updatedAt = 1))
        db.trackDao().upsertTrack(TrackEntity("track:local:42", null, null, "Title", "title", "Artist",
            canonicalAudioSourceType = "local", isLiked = false, isDownloadedByAura = false, createdAt = 1, updatedAt = 1))
        var checks = 0
        val failure = runCatching { library.addTrackToPlaylist("mix", "track:local:42", isCurrent = { ++checks < 2 }) }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(db.playlistDao().getPlaylistItems("mix").isEmpty())
        assertEquals(1L, db.playlistDao().getPlaylistEntity("mix")?.updatedAt)
        assertTrue(db.syncOutboxDao().getUnresolvedPlaylistOperations().isEmpty())
    }
}
