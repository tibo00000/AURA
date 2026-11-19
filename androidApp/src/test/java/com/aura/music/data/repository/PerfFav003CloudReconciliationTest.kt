package com.aura.music.data.repository

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.aura.music.core.AuthSessionManager
import com.aura.music.core.MemorySessionStore
import com.aura.music.core.StoredSession
import com.aura.music.core.token
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.AuraDatabaseConstructor
import com.aura.music.data.local.DownloadJobEntity
import com.aura.music.data.local.PlaylistEntity
import com.aura.music.data.local.PlaylistItemEntity
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.local.TrackLikeEntity
import com.aura.music.data.local.TrackMediaLinkEntity
import com.aura.music.data.media.MediaStoreAudioDataSource
import com.aura.music.data.network.AuraApiService
import com.aura.music.ui.utils.TrackLookupIndex
import com.aura.music.data.network.ApiError
import com.aura.music.data.network.AuraResponse
import com.aura.music.data.network.KtorAuraApiService
import com.aura.music.data.network.SyncedFileDeleteResponse
import com.aura.music.data.network.SyncedFileListResponseData
import com.aura.music.data.network.SyncedFileResponseData
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
class PerfFav003CloudReconciliationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var testFilesRoot: File
    private lateinit var context: Context
    private lateinit var db: AuraDatabase
    private val clientsToClose = mutableListOf<HttpClient>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication()
        testFilesRoot = File(temp.root, "files").apply { mkdirs() }
        val rootDir = testFilesRoot
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = rootDir
        }
        db = Room.databaseBuilder<AuraDatabase>(
            context = context,
            name = File(temp.root, "perf-fav-003.db").absolutePath,
            factory = { AuraDatabaseConstructor.initialize() }
        ).setDriver(AndroidSQLiteDriver()).build()
        // Schema/native SQLite initialization is fixture setup, not fetch coalescing latency.
        runBlocking { db.trackDao().getAllTracks() }
    }

    @After
    fun tearDown() {
        clientsToClose.forEach { runCatching { it.close() } }
        db.close()
    }

    private fun newAuthManager(user: String = "A"): AuthSessionManager =
        AuthSessionManager(
            store = MemorySessionStore(StoredSession(token(user), "refresh-$user", user, null)),
            signIn = { _, _ -> error("Unused in test") },
            refresh = { error("Unused in test") },
            nowSeconds = { 1000L }
        )

    private fun newCloudRepo(
        auth: AuthSessionManager,
        onChanged: () -> Unit = {},
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): CloudFileRepository {
        val client = HttpClient(MockEngine { request ->
            handler(request)
        }) {
            install(ContentNegotiation) { json(json) }
        }
        clientsToClose.add(client)
        val apiService = KtorAuraApiService(client, "https://api.example.invalid")
        return CloudFileRepository(
            database = db,
            apiService = apiService,
            context = context,
            authTokenProvider = { auth.getBearerHeader() },
            authSessionManagerProvider = { auth },
            onLibraryChanged = onChanged,
        )
    }

    private fun MockRequestHandleScope.jsonOk(payload: Any): HttpResponseData {
        val serialized = when (payload) {
            is AuraResponse<*> -> when (val d = payload.data) {
                is SyncedFileListResponseData -> json.encodeToString(AuraResponse(data = d))
                is SyncedFileResponseData -> json.encodeToString(AuraResponse(data = d))
                is SyncedFileDeleteResponse -> json.encodeToString(AuraResponse(data = d))
                else -> json.encodeToString(AuraResponse<SyncedFileListResponseData>(error = payload.error))
            }
            else -> error("Unsupported payload type")
        }
        return respond(
            content = serialized,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json")
        )
    }

    @Test
    fun completedPlaylistAcquisition_refreshesWarmLibraryAndSearch_withoutRepeatedInvalidation() = runBlocking {
        val auth = newAuthManager("A")
        val unusedApi = java.lang.reflect.Proxy.newProxyInstance(
            AuraApiService::class.java.classLoader, arrayOf(AuraApiService::class.java)
        ) { _, _, _ -> error("Local search must not request the network") } as AuraApiService
        val library = LocalLibraryRepository(
            database = db,
            mediaStoreAudioDataSource = MediaStoreAudioDataSource(context),
            syncRepositoryProvider = { error("Unused in local search") },
            apiService = unusedApi,
            context = context,
        )
        val trackId = "spotify:track:paroboy"
        db.trackDao().upsertTrack(TrackEntity(
            id = trackId, primaryArtistId = null, albumId = null,
            title = "paroboy", normalizedTitle = "paroboy", displayArtistName = "disiz",
            displayAlbumTitle = "on s'en rappellera pas", durationMs = 207_666L,
            canonicalAudioSourceType = "online", isLiked = false, isDownloadedByAura = false,
            createdAt = 10L, updatedAt = 10L,
        ))
        val oldIndex = library.getOrBuildSearchIndex()
        assertTrue(library.searchLocalTracks("paroboy").isEmpty())
        var items = emptyList<SyncedFileResponseData>()
        var invalidations = 0
        val repo = newCloudRepo(auth, onChanged = {
            invalidations++
            library.invalidateSearchIndex()
        }) { jsonOk(AuraResponse(data = SyncedFileListResponseData(items))) }
        assertTrue(repo.getOrFetchCloudFiles().isSuccess)
        items = listOf(SyncedFileResponseData(
            trackId = trackId, title = "paroboy", artistName = "disiz",
            albumTitle = "on s'en rappellera pas", durationMs = 207_666L,
        ))
        assertTrue(repo.getOrFetchCloudFiles(forceRefresh = true).isSuccess)
        assertEquals("cloud", db.trackDao().getRawTrackById(trackId)?.canonicalAudioSourceType)
        assertTrue(library.getAllTracks().any { it.id == trackId })
        assertEquals(trackId, library.searchLocalTracks("paroboy").single().id)
        assertTrue(repo.syncedTrackIds.value.contains(trackId))
        assertTrue(TrackLookupIndex.buildCloudOnly(repo.cachedCloudFiles.value, repo.syncedTrackIds.value)
            .isCloudSynced("deezer:track:other-id", "paroboy", "disiz", "on s'en rappellera pas"))
        val freshIndex = library.getOrBuildSearchIndex()
        assertFalse(oldIndex === freshIndex)
        assertEquals(1, invalidations)
        assertTrue(repo.getOrFetchCloudFiles(forceRefresh = true).isSuccess)
        assertEquals(1, invalidations)
        assertTrue(freshIndex === library.getOrBuildSearchIndex())
    }

    @Test
    fun sourceSnapshotTrackGetsMissingArtistPageWithoutOverwritingManualMetadata() = runBlocking {
        val auth = newAuthManager("A")
        val repo = newCloudRepo(auth) { jsonOk(AuraResponse(data = SyncedFileListResponseData(emptyList()))) }
        val track = TrackEntity(
            id = "cloud:source-snapshot", primaryArtistId = null, albumId = null,
            title = "Monde", normalizedTitle = "monde", displayArtistName = "Luidji, Ryan Koffi",
            displayAlbumTitle = "Saison 00", canonicalAudioSourceType = "online",
            isLiked = false, isDownloadedByAura = false,
            createdAt = 1L, updatedAt = 1L,
        )
        db.trackDao().upsertTrack(track)
        val item = SyncedFileResponseData(trackId = track.id, title = track.title,
            artistName = track.displayArtistName, albumTitle = track.displayAlbumTitle,
            artistId = "artist:luidji, ryan koffi", albumId = "album:saison-00")
        repo.reconcileCloudTracksWithDatabase(listOf(item))
        val attached = requireNotNull(db.trackDao().getRawTrackById(track.id))
        assertEquals(item.artistId, attached.primaryArtistId)
        assertEquals(item.albumId, attached.albumId)
        assertNotNull(db.artistDao().getArtistDetail(requireNotNull(item.artistId)))
        repo.reconcileCloudTracksWithDatabase(listOf(item))
        assertEquals(attached, db.trackDao().getRawTrackById(track.id))
        db.trackDao().upsertTrack(attached.copy(primaryArtistId = null, albumId = null,
            displayArtistName = "Manual artist", displayAlbumTitle = "Manual album"))
        repo.reconcileCloudTracksWithDatabase(listOf(item))
        val manual = requireNotNull(db.trackDao().getRawTrackById(track.id))
        assertNull(manual.primaryArtistId)
        assertNull(manual.albumId)
        assertEquals("Manual artist", manual.displayArtistName)
        assertEquals("Manual album", manual.displayAlbumTitle)
    }

    @Test
    fun explicitCloudArtistCorrectionAppliesOnceAndPreservesALaterManualChoice() = runBlocking {
        val auth = newAuthManager("A")
        val oldId = "artist:wrong"
        val newId = "artist:correct"
        db.artistDao().insertArtistsIgnore(listOf(com.aura.music.data.local.ArtistEntity(
            oldId, "Wrong artist", "wrong artist", createdAt = 1, updatedAt = 1)))
        val track = TrackEntity("cloud:correction", oldId, null, "Song", "song", "Wrong artist",
            canonicalAudioSourceType = "cloud", isLiked = true, isDownloadedByAura = false,
            createdAt = 1, updatedAt = 1)
        db.trackDao().upsertTrack(track)
        val item = SyncedFileResponseData(trackId = track.id, title = track.title, artistName = "Correct artist",
            artistId = newId, artistCorrection = com.aura.music.data.network.CloudArtistCorrection("Wrong artist", oldId))
        val repo = newCloudRepo(auth) { jsonOk(AuraResponse(data = SyncedFileListResponseData(listOf(item)))) }
        assertTrue(repo.getOrFetchCloudFiles(forceRefresh = true).isSuccess)
        val fixed = requireNotNull(db.trackDao().getRawTrackById(track.id))
        assertEquals("Correct artist", fixed.displayArtistName)
        assertEquals(newId, fixed.primaryArtistId)
        assertTrue(fixed.isLiked)
        assertTrue(repo.getOrFetchCloudFiles(forceRefresh = true).isSuccess)
        assertEquals(fixed, db.trackDao().getRawTrackById(track.id))
        db.trackDao().upsertTrack(fixed.copy(displayArtistName = "My manual artist"))
        assertTrue(repo.getOrFetchCloudFiles(forceRefresh = true).isSuccess)
        assertEquals("My manual artist", db.trackDao().getRawTrackById(track.id)?.displayArtistName)
    }

    @Test
    fun reconcileCloudTracksWithDatabase_withUnknownArtist_isIdempotentAndSkipsWrites() = runBlocking {
        val auth = newAuthManager("A")
        val repo = newCloudRepo(auth) { jsonOk(AuraResponse(data = SyncedFileListResponseData(emptyList()))) }

        val items = listOf(
            SyncedFileResponseData(
                trackId = "cloud:track:unknown-artist",
                title = "Mystery Melody",
                artistName = null,
                albumTitle = null,
                durationMs = 190_000L,
                artistId = null,
                albumId = null,
                coverUri = "https://cdn.example/cover1.jpg"
            ),
            SyncedFileResponseData(
                trackId = "cloud:track:known-artist",
                title = "Known Song",
                artistName = "Real Artist",
                albumTitle = "Real Album",
                durationMs = 210_000L,
                artistId = "art_known",
                albumId = "alb_known",
                coverUri = "https://cdn.example/cover2.jpg"
            )
        )

        withTimeout(5_000L) { repo.reconcileCloudTracksWithDatabase(items) }

        val firstUnknown = db.trackDao().getRawTrackById("cloud:track:unknown-artist")!!
        val firstKnown = db.trackDao().getRawTrackById("cloud:track:known-artist")!!
        assertEquals("Artiste Inconnu", firstUnknown.displayArtistName)

        // Mark both liked and observe Room liked flow stability
        db.trackDao().upsertTrack(firstUnknown.copy(isLiked = true))
        db.trackDao().upsertTrack(firstKnown.copy(isLiked = true))
        db.trackLikeDao().insertLike(TrackLikeEntity(firstUnknown.id, likedAt = 100L))
        db.trackLikeDao().insertLike(TrackLikeEntity(firstKnown.id, likedAt = 200L))

        val likedBefore2 = db.trackDao().getRawTrackById(firstUnknown.id)!!
        val knownBefore2 = db.trackDao().getRawTrackById(firstKnown.id)!!

        val emissions = Collections.synchronizedList(mutableListOf<List<String>>())
        val firstReady = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.IO) {
            db.trackDao().getLikedTracksFlow().distinctUntilChanged().collect { rows ->
                emissions.add(rows.map { it.id })
                firstReady.complete(Unit)
            }
        }
        try {
            withTimeout(5_000L) { firstReady.await() }
            assertEquals(1, emissions.size)

            delay(50)
            withTimeout(5_000L) { repo.reconcileCloudTracksWithDatabase(items) }
            delay(150)

            val unknownAfter2 = db.trackDao().getRawTrackById(firstUnknown.id)!!
            val knownAfter2 = db.trackDao().getRawTrackById(firstKnown.id)!!
            assertEquals(likedBefore2.updatedAt, unknownAfter2.updatedAt)
            assertEquals(knownBefore2.updatedAt, knownAfter2.updatedAt)
            assertEquals(1, emissions.size)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun getOrFetchCloudFiles_reusesCacheAndRefreshesOnForceRefresh() = runBlocking {
        val auth = newAuthManager("A")
        val listCalls = AtomicInteger(0)
        var serverItems = listOf(
            SyncedFileResponseData(trackId = "cloud:1", title = "One", artistName = "A1")
        )

        val repo = newCloudRepo(auth) {
            listCalls.incrementAndGet()
            jsonOk(AuraResponse(data = SyncedFileListResponseData(serverItems)))
        }

        val first = withTimeout(5_000L) { repo.getOrFetchCloudFiles(forceRefresh = false) }
        assertTrue(first.isSuccess)
        assertEquals(1, first.getOrThrow().size)
        assertEquals(1, listCalls.get())

        val cached = withTimeout(5_000L) { repo.getOrFetchCloudFiles(forceRefresh = false) }
        val fromFlow = withTimeout(5_000L) { repo.listCloudFiles().first() }
        assertEquals(1, cached.getOrThrow().size)
        assertEquals(1, fromFlow.getOrThrow().size)
        assertEquals(1, listCalls.get())

        // Mutate server and force refresh
        serverItems = listOf(
            SyncedFileResponseData(trackId = "cloud:1", title = "One", artistName = "A1"),
            SyncedFileResponseData(trackId = "cloud:2", title = "Two", artistName = "A2")
        )
        val forced = withTimeout(5_000L) { repo.getOrFetchCloudFiles(forceRefresh = true) }
        assertEquals(2, forced.getOrThrow().size)
        assertEquals(2, listCalls.get())
        assertEquals(listOf("cloud:1", "cloud:2"), repo.cachedCloudFiles.value.map { it.trackId })
    }

    @Test
    fun concurrentFetchCoalescesRequests_andMidFlightForceRefreshIsNotLost() = runBlocking {
        val auth = newAuthManager("A")
        val listCalls = AtomicInteger(0)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val itemV1 = SyncedFileResponseData(trackId = "cloud:v1", title = "V1", artistName = "Artist")
        val itemV2 = SyncedFileResponseData(trackId = "cloud:v2", title = "V2", artistName = "Artist")

        val repo = newCloudRepo(auth) {
            val callNumber = listCalls.incrementAndGet()
            if (callNumber == 1) {
                firstEntered.complete(Unit)
                releaseFirst.await()
                jsonOk(AuraResponse(data = SyncedFileListResponseData(listOf(itemV1))))
            } else {
                jsonOk(AuraResponse(data = SyncedFileListResponseData(listOf(itemV1, itemV2))))
            }
        }

        val d1 = async { repo.getOrFetchCloudFiles(forceRefresh = false) }
        withTimeout(5_000L) { firstEntered.await() }

        // Coalesced concurrent callers + mid-flight forced refresh
        val d2 = async { repo.getOrFetchCloudFiles(forceRefresh = false) }
        val d3 = async { repo.refreshSyncedTrackIds() }
        val dForced = async { repo.getOrFetchCloudFiles(forceRefresh = true) }

        delay(40)
        assertEquals(1, listCalls.get())
        releaseFirst.complete(Unit)

        val r1 = withTimeout(5_000L) { d1.await() }
        val r2 = withTimeout(5_000L) { d2.await() }
        withTimeout(5_000L) { d3.await() }
        val rForced = withTimeout(5_000L) { dForced.await() }

        assertEquals(listOf("cloud:v1"), r1.getOrThrow().map { it.trackId })
        assertEquals(listOf("cloud:v1"), r2.getOrThrow().map { it.trackId })
        assertEquals(listOf("cloud:v1", "cloud:v2"), rForced.getOrThrow().map { it.trackId })
        assertEquals(2, listCalls.get())
        assertEquals(setOf("cloud:v1", "cloud:v2"), repo.syncedTrackIds.value)
    }

    @Test
    fun reconcileAndDeleteSyncFile_preserveLikedAndDownloadedTracks() = runBlocking {
        val auth = newAuthManager("A")
        val deletedPaths = Collections.synchronizedList(mutableListOf<String>())
        val repo = newCloudRepo(auth) { request ->
            if (request.method == HttpMethod.Delete) {
                val trackId = request.url.encodedPath.substringAfterLast('/')
                deletedPaths.add(trackId)
                jsonOk(AuraResponse(data = SyncedFileDeleteResponse(trackId = trackId, deleted = true)))
            } else {
                jsonOk(AuraResponse(data = SyncedFileListResponseData(emptyList())))
            }
        }

        // Seed 1) liked cloud-only track, 2) unliked cloud-only track, 3) downloaded offline track
        db.trackDao().upsertTracks(
            listOf(
                TrackEntity(
                    id = "cloud:liked",
                    primaryArtistId = null,
                    albumId = null,
                    title = "Liked Cloud",
                    normalizedTitle = "liked-cloud",
                    displayArtistName = "Artist",
                    canonicalAudioSourceType = "cloud",
                    isLiked = true,
                    isDownloadedByAura = false,
                    createdAt = 10L,
                    updatedAt = 10L
                ),
                TrackEntity(
                    id = "cloud:orphan",
                    primaryArtistId = null,
                    albumId = null,
                    title = "Orphan Cloud",
                    normalizedTitle = "orphan-cloud",
                    displayArtistName = "Artist",
                    canonicalAudioSourceType = "cloud",
                    isLiked = false,
                    isDownloadedByAura = false,
                    createdAt = 10L,
                    updatedAt = 10L
                ),
                TrackEntity(
                    id = "spotify:track:offline",
                    primaryArtistId = null,
                    albumId = null,
                    title = "Downloaded Song",
                    normalizedTitle = "downloaded-song",
                    displayArtistName = "Artist",
                    canonicalAudioSourceType = "downloaded",
                    isLiked = true,
                    isDownloadedByAura = true,
                    createdAt = 20L,
                    updatedAt = 20L
                )
            )
        )
        db.trackLikeDao().insertLike(TrackLikeEntity("cloud:liked", likedAt = 100L))
        db.trackLikeDao().insertLike(TrackLikeEntity("spotify:track:offline", likedAt = 200L))
        db.trackDao().upsertTrackMediaLinks(
            listOf(
                TrackMediaLinkEntity(
                    id = "media:offline",
                    trackId = "spotify:track:offline",
                    mediaStoreId = -1L,
                    contentUri = "file:///downloads/offline.mp3",
                    fileSizeBytes = 4096L,
                    mimeType = "audio/mpeg",
                    dateModifiedEpochMs = 20L,
                    availabilityStatus = "present",
                    lastScannedAt = 20L
                )
            )
        )

        // Reconciliation adds new track and purges only unliked cloud-only orphan
        withTimeout(5_000L) {
            repo.reconcileCloudTracksWithDatabase(
                listOf(SyncedFileResponseData(trackId = "cloud:temp", title = "Temp Cloud", artistName = "Temp Artist"))
            )
        }
        assertNull(db.trackDao().getRawTrackById("cloud:orphan"))
        assertNotNull(db.trackDao().getRawTrackById("cloud:liked"))
        assertNotNull(db.trackDao().getRawTrackById("spotify:track:offline"))
        assertNotNull(db.trackDao().getRawTrackById("cloud:temp"))

        // deleteSyncFile preserves liked track and downloaded track, deletes unliked cloud-only track
        assertTrue(withTimeout(5_000L) { repo.deleteSyncFile("cloud:liked").first() }.isSuccess)
        assertTrue(withTimeout(5_000L) { repo.deleteSyncFile("spotify:track:offline").first() }.isSuccess)
        assertTrue(withTimeout(5_000L) { repo.deleteSyncFile("cloud:temp").first() }.isSuccess)

        assertNotNull(db.trackDao().getRawTrackById("cloud:liked"))
        assertTrue(db.trackLikeDao().isTrackLiked("cloud:liked"))
        val offlineTrack = db.trackDao().getRawTrackById("spotify:track:offline")!!
        assertEquals("downloaded", offlineTrack.canonicalAudioSourceType)
        assertTrue(db.trackLikeDao().isTrackLiked("spotify:track:offline"))
        assertNull(db.trackDao().getRawTrackById("cloud:temp"))
    }

    @Test
    fun accountSwitchAndLogout_clearCacheAndPreventCrossAccountBleed() = runBlocking {
        val auth = newAuthManager("A")
        val listCalls = AtomicInteger(0)
        val inFlightEntered = CompletableDeferred<Unit>()
        val releaseInFlight = CompletableDeferred<Unit>()
        var blockB = false

        val repo = newCloudRepo(auth) { request ->
            val call = listCalls.incrementAndGet()
            val header = request.headers[HttpHeaders.Authorization].orEmpty()
            if (header.contains(token("A"))) {
                jsonOk(
                    AuraResponse(
                        data = SyncedFileListResponseData(
                            listOf(SyncedFileResponseData(trackId = "cloud:userA", title = "Song A", artistName = "Artist A"))
                        )
                    )
                )
            } else {
                if (blockB && call == 2) {
                    inFlightEntered.complete(Unit)
                    releaseInFlight.await()
                }
                jsonOk(
                    AuraResponse(
                        data = SyncedFileListResponseData(
                            listOf(SyncedFileResponseData(trackId = "cloud:userB", title = "Song B", artistName = "Artist B"))
                        )
                    )
                )
            }
        }

        // User A populates cache
        val resA = withTimeout(5_000L) { repo.getOrFetchCloudFiles(false) }
        assertEquals(listOf("cloud:userA"), resA.getOrThrow().map { it.trackId })
        assertTrue(repo.isCloudTrackSynced("cloud:userA"))
        assertNotNull(repo.getCachedCloudFile("cloud:userA"))

        // Logout clears cache synchronously via scope guard and reactively via collector
        auth.logout()
        assertFalse(repo.isCloudTrackSynced("cloud:userA"))
        assertNull(repo.getCachedCloudFile("cloud:userA"))
        val emptyDeadline = System.currentTimeMillis() + 2_000L
        while (repo.cachedCloudFiles.value.isNotEmpty() && System.currentTimeMillis() < emptyDeadline) {
            delay(10)
        }
        assertTrue(repo.cachedCloudFiles.value.isEmpty())
        assertTrue(repo.syncedTrackIds.value.isEmpty())

        val afterLogout = withTimeout(5_000L) { repo.getOrFetchCloudFiles(false) }
        assertTrue(afterLogout.getOrThrow().isEmpty())
        assertEquals(1, listCalls.get())

        // Account B logs in -> mid-flight logout during B fetch must not populate cache or DB
        blockB = true
        auth.saveSession(token("B"), "refresh-B", "B", null)
        val inFlightJob = async { repo.getOrFetchCloudFiles(forceRefresh = true) }
        withTimeout(5_000L) { inFlightEntered.await() }
        auth.logout()
        releaseInFlight.complete(Unit)
        withTimeout(5_000L) { inFlightJob.await() }

        assertNull(db.trackDao().getRawTrackById("cloud:userB"))
        assertTrue(repo.cachedCloudFiles.value.isEmpty())

        // User B logs back in cleanly -> receives User B items without User A bleed
        blockB = false
        auth.saveSession(token("B"), "refresh-B2", "B", null)
        val resB = withTimeout(5_000L) { repo.getOrFetchCloudFiles(false) }
        assertEquals(listOf("cloud:userB"), resB.getOrThrow().map { it.trackId })
        assertFalse(repo.isCloudTrackSynced("cloud:userA"))
        assertTrue(repo.isCloudTrackSynced("cloud:userB"))
    }

    @Test
    fun networkFailure_preservesExistingRoomState_andRecoversOnRetry() = runBlocking {
        val auth = newAuthManager("A")
        db.trackDao().upsertTrack(
            TrackEntity(
                id = "cloud:keep",
                primaryArtistId = null,
                albumId = null,
                title = "Existing Favorite",
                normalizedTitle = "existing-favorite",
                displayArtistName = "Artist",
                canonicalAudioSourceType = "cloud",
                isLiked = true,
                isDownloadedByAura = false,
                createdAt = 10L,
                updatedAt = 10L
            )
        )
        db.trackLikeDao().insertLike(TrackLikeEntity("cloud:keep", likedAt = 100L))

        var shouldFail = true
        val repo = newCloudRepo(auth) {
            if (shouldFail) {
                respond(
                    content = json.encodeToString(
                        AuraResponse<SyncedFileListResponseData>(
                            error = ApiError(code = "upstream_error", message = "Backend down")
                        )
                    ),
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                jsonOk(
                    AuraResponse(
                        data = SyncedFileListResponseData(
                            listOf(
                                SyncedFileResponseData(trackId = "cloud:keep", title = "Existing Favorite", artistName = "Artist"),
                                SyncedFileResponseData(trackId = "cloud:recovered", title = "Recovered Song", artistName = "Artist 2")
                            )
                        )
                    )
                )
            }
        }

        val failedResult = withTimeout(5_000L) { repo.getOrFetchCloudFiles(forceRefresh = false) }
        assertTrue(failedResult.isFailure)
        assertNotNull(db.trackDao().getRawTrackById("cloud:keep"))
        assertTrue(db.trackLikeDao().isTrackLiked("cloud:keep"))
        assertNull(db.trackDao().getRawTrackById("cloud:recovered"))

        // Failed call must not poison cache; next call recovers cleanly without forceRefresh=true
        shouldFail = false
        val recoveredResult = withTimeout(5_000L) { repo.getOrFetchCloudFiles(forceRefresh = false) }
        assertTrue(recoveredResult.isSuccess)
        assertEquals(listOf("cloud:keep", "cloud:recovered"), recoveredResult.getOrThrow().map { it.trackId })
        assertNotNull(db.trackDao().getRawTrackById("cloud:recovered"))
    }

    @Test
    fun refreshSyncedTrackIds_forcesRefreshEvenWhenCacheWarm_andCoalescesForcedWaiters() = runBlocking {
        val auth = newAuthManager("A")
        val listCalls = AtomicInteger(0)
        val refreshEntered = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val itemV1 = SyncedFileResponseData(trackId = "cloud:warm1", title = "Warm 1", artistName = "Artist")
        val itemV2 = SyncedFileResponseData(trackId = "cloud:warm2", title = "Warm 2", artistName = "Artist")
        val itemV3 = SyncedFileResponseData(trackId = "cloud:warm3", title = "Warm 3", artistName = "Artist")

        val repo = newCloudRepo(auth) {
            when (listCalls.incrementAndGet()) {
                1 -> jsonOk(AuraResponse(data = SyncedFileListResponseData(listOf(itemV1))))
                2 -> {
                    refreshEntered.complete(Unit)
                    releaseRefresh.await()
                    jsonOk(AuraResponse(data = SyncedFileListResponseData(listOf(itemV1, itemV2))))
                }
                else -> jsonOk(AuraResponse(data = SyncedFileListResponseData(listOf(itemV1, itemV2, itemV3))))
            }
        }

        // Warm the cache first (call 1)
        val initial = withTimeout(5_000L) { repo.getOrFetchCloudFiles(forceRefresh = false) }
        assertTrue(initial.isSuccess)
        assertEquals(listOf("cloud:warm1"), repo.cachedCloudFiles.value.map { it.trackId })
        assertEquals(1, listCalls.get())

        // Even with a warm cache, refreshSyncedTrackIds() forces call 2
        val refreshJob = async { repo.refreshSyncedTrackIds() }
        withTimeout(5_000L) { refreshEntered.await() }

        // While call 2 is in flight, non-forced read returns warm cache immediately without blocking,
        // whereas concurrent forceRefresh=true waiters coalesce onto a single post-threshold fetch (call 3).
        val concurrentForced1 = async { repo.getOrFetchCloudFiles(forceRefresh = true) }
        val concurrentForced2 = async { repo.getOrFetchCloudFiles(forceRefresh = true) }
        val concurrentCached = async { repo.getOrFetchCloudFiles(forceRefresh = false) }

        val cachedResult = withTimeout(5_000L) { concurrentCached.await() }
        assertEquals(listOf("cloud:warm1"), cachedResult.getOrThrow().map { it.trackId })
        delay(40)

        releaseRefresh.complete(Unit)
        withTimeout(5_000L) { refreshJob.await() }
        val forcedResult1 = withTimeout(5_000L) { concurrentForced1.await() }
        val forcedResult2 = withTimeout(5_000L) { concurrentForced2.await() }

        assertEquals(listOf("cloud:warm1", "cloud:warm2", "cloud:warm3"), forcedResult1.getOrThrow().map { it.trackId })
        assertEquals(listOf("cloud:warm1", "cloud:warm2", "cloud:warm3"), forcedResult2.getOrThrow().map { it.trackId })
        assertEquals(listOf("cloud:warm1", "cloud:warm2", "cloud:warm3"), repo.cachedCloudFiles.value.map { it.trackId })
        assertEquals(setOf("cloud:warm1", "cloud:warm2", "cloud:warm3"), repo.syncedTrackIds.value)
        assertEquals(3, listCalls.get())
    }

    @Test
    fun uploadTrack_updatesWarmCache_skipsColdCache_andAbortsCacheMutationOnLogout() = runBlocking {
        val auth = newAuthManager("A")
        val listCalls = AtomicInteger(0)
        val uploadEntered = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        var blockNextUpload = false

        val repo = newCloudRepo(auth) { request ->
            if (request.method == HttpMethod.Post) {
                val trackId = request.url.encodedPath.substringAfterLast('/')
                if (blockNextUpload) {
                    uploadEntered.complete(Unit)
                    releaseUpload.await()
                }
                jsonOk(
                    AuraResponse(
                        data = SyncedFileResponseData(
                            trackId = trackId,
                            title = "Uploaded $trackId",
                            artistName = "Artist",
                            coverUri = "https://cdn.example/$trackId.jpg"
                        )
                    )
                )
            } else {
                listCalls.incrementAndGet()
                jsonOk(
                    AuraResponse(
                        data = SyncedFileListResponseData(
                            listOf(
                                SyncedFileResponseData(
                                    trackId = "cloud:existing",
                                    title = "Existing",
                                    artistName = "Artist"
                                ),
                                SyncedFileResponseData(
                                    trackId = "local:u1",
                                    title = "Uploaded local:u1",
                                    artistName = "Artist",
                                    coverUri = "https://cdn.example/local:u1.jpg"
                                )
                            )
                        )
                    )
                )
            }
        }

        suspend fun seedUploadableTrack(id: String, fileName: String) {
            val audioFile = File(testFilesRoot, fileName).apply { writeBytes("ID3-audio".toByteArray()) }
            db.trackDao().upsertTrack(
                TrackEntity(
                    id = id,
                    primaryArtistId = null,
                    albumId = null,
                    title = "Track $id",
                    normalizedTitle = "track-$id",
                    displayArtistName = "Artist",
                    coverUri = "https://cdn.example/$id.jpg",
                    canonicalAudioSourceType = "local",
                    isLiked = false,
                    isDownloadedByAura = false,
                    createdAt = 10L,
                    updatedAt = 10L
                )
            )
            db.trackDao().upsertTrackMediaLinks(
                listOf(
                    TrackMediaLinkEntity(
                        id = "link-$id",
                        trackId = id,
                        mediaStoreId = id.hashCode().toLong(),
                        contentUri = audioFile.toURI().toString(),
                        availabilityStatus = "available",
                        lastScannedAt = 10L
                    )
                )
            )
        }

        seedUploadableTrack("local:u1", "u1.mp3")
        seedUploadableTrack("local:u2", "u2.mp3")
        seedUploadableTrack("local:u3", "u3.mp3")

        // 1) Cold cache upload: updates syncedTrackIds ONLY, keeps cachedCloudFiles empty so full GET is not bypassed
        val coldUpload = withTimeout(5_000L) { repo.uploadTrack("local:u1").last() }
        assertTrue(coldUpload.isSuccess)
        assertTrue(repo.syncedTrackIds.value.contains("local:u1"))
        assertTrue(repo.cachedCloudFiles.value.isEmpty())

        val warmed = withTimeout(5_000L) { repo.getOrFetchCloudFiles(forceRefresh = false) }
        assertTrue(warmed.isSuccess)
        assertEquals(1, listCalls.get())
        assertEquals(listOf("cloud:existing", "local:u1"), repo.cachedCloudFiles.value.map { it.trackId })

        // 2) Warm cache upload: appends into both cachedCloudFiles and syncedTrackIds
        val warmUpload = withTimeout(5_000L) { repo.uploadTrack("local:u2").last() }
        assertTrue(warmUpload.isSuccess)
        assertEquals(listOf("cloud:existing", "local:u1", "local:u2"), repo.cachedCloudFiles.value.map { it.trackId })
        assertTrue(repo.syncedTrackIds.value.contains("local:u2"))

        // 3) Logout mid-upload: abort cache/ID updates cleanly
        blockNextUpload = true
        val uploadJob = async { repo.uploadTrack("local:u3").last() }
        withTimeout(5_000L) { uploadEntered.await() }
        auth.logout()
        releaseUpload.complete(Unit)
        withTimeout(5_000L) { uploadJob.await() }

        assertTrue(repo.cachedCloudFiles.value.isEmpty())
        assertTrue(repo.syncedTrackIds.value.isEmpty())
    }

    @Test
    fun reconcileAndDeleteSyncFile_preserveLocalCovers_playlistTracks_andActiveDownloadJobs() = runBlocking {
        val auth = newAuthManager("A")
        val deletedPaths = Collections.synchronizedList(mutableListOf<String>())
        val repo = newCloudRepo(auth) { request ->
            if (request.method == HttpMethod.Delete) {
                val trackId = request.url.encodedPath.substringAfterLast('/')
                deletedPaths.add(trackId)
                jsonOk(AuraResponse(data = SyncedFileDeleteResponse(trackId = trackId, deleted = true)))
            } else {
                jsonOk(AuraResponse(data = SyncedFileListResponseData(emptyList())))
            }
        }

        val localCoverTrack = TrackEntity(
            id = "cloud:local-cover",
            primaryArtistId = null,
            albumId = null,
            title = "Titre inconnu",
            normalizedTitle = "titre inconnu",
            displayArtistName = "Artist",
            coverUri = "file:///data/user/0/app/covers/local.jpg",
            canonicalAudioSourceType = "cloud",
            isLiked = false,
            isDownloadedByAura = false,
            createdAt = 10L,
            updatedAt = 10L
        )
        val contentCoverTrack = TrackEntity(
            id = "cloud:content-cover",
            primaryArtistId = null,
            albumId = null,
            title = "Titre inconnu",
            normalizedTitle = "titre inconnu",
            displayArtistName = "Artist",
            coverUri = "content://com.aura.music.artwork/covers/art.jpg",
            canonicalAudioSourceType = "cloud",
            isLiked = false,
            isDownloadedByAura = false,
            createdAt = 10L,
            updatedAt = 10L
        )
        val nullCoverTrack = TrackEntity(
            id = "cloud:null-cover",
            primaryArtistId = null,
            albumId = null,
            title = "Null Cover Track",
            normalizedTitle = "null-cover-track",
            displayArtistName = "Artist",
            coverUri = null,
            canonicalAudioSourceType = "cloud",
            isLiked = false,
            isDownloadedByAura = false,
            createdAt = 10L,
            updatedAt = 10L
        )
        val playlistCloudTrack = TrackEntity(
            id = "cloud:in-playlist",
            primaryArtistId = null,
            albumId = null,
            title = "In Playlist",
            normalizedTitle = "in-playlist",
            displayArtistName = "Artist",
            canonicalAudioSourceType = "cloud",
            isLiked = false,
            isDownloadedByAura = false,
            createdAt = 10L,
            updatedAt = 10L
        )
        val activeJobCloudTrack = TrackEntity(
            id = "cloud:active-dl",
            primaryArtistId = null,
            albumId = null,
            title = "Active Download",
            normalizedTitle = "active-download",
            displayArtistName = "Artist",
            canonicalAudioSourceType = "cloud",
            isLiked = false,
            isDownloadedByAura = false,
            createdAt = 10L,
            updatedAt = 10L
        )
        val disposableCloudTrack = TrackEntity(
            id = "cloud:disposable",
            primaryArtistId = null,
            albumId = null,
            title = "Disposable",
            normalizedTitle = "disposable",
            displayArtistName = "Artist",
            canonicalAudioSourceType = "cloud",
            isLiked = false,
            isDownloadedByAura = false,
            createdAt = 10L,
            updatedAt = 10L
        )

        db.trackDao().upsertTracks(
            listOf(
                localCoverTrack,
                contentCoverTrack,
                nullCoverTrack,
                playlistCloudTrack,
                activeJobCloudTrack,
                disposableCloudTrack
            )
        )
        db.playlistDao().insertPlaylist(
            PlaylistEntity(
                id = "pl-1",
                name = "Keep",
                isPinned = false,
                createdAt = 10L,
                updatedAt = 10L
            )
        )
        db.playlistDao().insertPlaylistItem(
            PlaylistItemEntity(
                id = "pli-1",
                playlistId = "pl-1",
                trackId = "cloud:in-playlist",
                position = 0,
                addedAt = 10L
            )
        )
        db.downloadJobDao().upsert(
            DownloadJobEntity(
                id = "job-1",
                trackId = "cloud:active-dl",
                providerName = "deezer",
                status = "running",
                createdAt = 10L,
                updatedAt = 10L
            )
        )

        // 1) Reconcile fills missing coverUri, updates metadata, yet preserves local/content covers and playlist + active-job tracks
        repo.reconcileCloudTracksWithDatabase(
            listOf(
                SyncedFileResponseData(
                    trackId = "cloud:local-cover",
                    title = "Local Cover Track",
                    artistName = "Artist",
                    coverUri = "https://remote.example/new1.jpg"
                ),
                SyncedFileResponseData(
                    trackId = "cloud:content-cover",
                    title = "Content Cover Track",
                    artistName = "Artist",
                    coverUri = "https://remote.example/new2.jpg"
                ),
                SyncedFileResponseData(
                    trackId = "cloud:null-cover",
                    title = "Null Cover Track",
                    artistName = "Artist",
                    coverUri = "https://remote.example/new3.jpg"
                )
            )
        )

        val localAfter = db.trackDao().getRawTrackById("cloud:local-cover")
        val contentAfter = db.trackDao().getRawTrackById("cloud:content-cover")
        assertEquals("Local Cover Track", localAfter?.title)
        assertEquals("file:///data/user/0/app/covers/local.jpg", localAfter?.coverUri)
        assertEquals("Content Cover Track", contentAfter?.title)
        assertEquals("content://com.aura.music.artwork/covers/art.jpg", contentAfter?.coverUri)
        assertEquals(
            "https://remote.example/new3.jpg",
            db.trackDao().getRawTrackById("cloud:null-cover")?.coverUri
        )
        assertNotNull(db.trackDao().getRawTrackById("cloud:in-playlist"))
        assertNotNull(db.trackDao().getRawTrackById("cloud:active-dl"))
        assertNull(db.trackDao().getRawTrackById("cloud:disposable"))

        // 2) Re-seed disposable track, then verify deleteSyncFile respects playlist & active download guards atomically
        db.trackDao().upsertTrack(disposableCloudTrack)

        assertTrue(withTimeout(5_000L) { repo.deleteSyncFile("cloud:in-playlist").first() }.isSuccess)
        assertTrue(withTimeout(5_000L) { repo.deleteSyncFile("cloud:active-dl").first() }.isSuccess)
        assertTrue(withTimeout(5_000L) { repo.deleteSyncFile("cloud:disposable").first() }.isSuccess)

        assertEquals(listOf("cloud:in-playlist", "cloud:active-dl", "cloud:disposable"), deletedPaths)
        assertNotNull(db.trackDao().getRawTrackById("cloud:in-playlist"))
        assertNotNull(db.trackDao().getRawTrackById("cloud:active-dl"))
        assertNull(db.trackDao().getRawTrackById("cloud:disposable"))
    }

    @Test
    fun accountSwitchMidFetch_abortsStaleCacheAndRoomWrites() = runBlocking {
        val auth = newAuthManager("A")
        val fetchAEntered = CompletableDeferred<Unit>()
        val releaseFetchA = CompletableDeferred<Unit>()

        val repo = newCloudRepo(auth) { request ->
            val authHeader = request.headers[HttpHeaders.Authorization].orEmpty()
            if (authHeader.contains(token("A"))) {
                fetchAEntered.complete(Unit)
                releaseFetchA.await()
                jsonOk(
                    AuraResponse(
                        data = SyncedFileListResponseData(
                            listOf(SyncedFileResponseData(trackId = "cloud:from-A", title = "Track A", artistName = "Artist A"))
                        )
                    )
                )
            } else {
                jsonOk(
                    AuraResponse(
                        data = SyncedFileListResponseData(
                            listOf(SyncedFileResponseData(trackId = "cloud:from-B", title = "Track B", artistName = "Artist B"))
                        )
                    )
                )
            }
        }

        val jobA = async { repo.getOrFetchCloudFiles(forceRefresh = true) }
        withTimeout(5_000L) { fetchAEntered.await() }

        // Switch account A -> B before A completes
        auth.saveSession(token("B"), "refresh-B", "B", null)
        releaseFetchA.complete(Unit)
        withTimeout(5_000L) { jobA.await() }

        // Account A's late response must not write Room or contaminate B's cache/IDs
        assertNull(db.trackDao().getRawTrackById("cloud:from-A"))
        assertFalse(repo.isCloudTrackSynced("cloud:from-A"))
        assertTrue(repo.cachedCloudFiles.value.isEmpty())

        // Account B fetches cleanly
        val resultB = withTimeout(5_000L) { repo.getOrFetchCloudFiles(forceRefresh = false) }
        assertTrue(resultB.isSuccess)
        assertEquals(listOf("cloud:from-B"), resultB.getOrThrow().map { it.trackId })
        assertNotNull(db.trackDao().getRawTrackById("cloud:from-B"))
        assertNull(db.trackDao().getRawTrackById("cloud:from-A"))
    }
}
