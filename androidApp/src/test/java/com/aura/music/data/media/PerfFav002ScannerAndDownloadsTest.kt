package com.aura.music.data.media

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
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.local.TrackLikeEntity
import com.aura.music.data.network.AuraResponse
import com.aura.music.data.network.DownloadJobListResponseData
import com.aura.music.data.network.DownloadJobResponseData
import com.aura.music.data.network.KtorAuraApiService
import com.aura.music.data.repository.DownloadRepository
import com.aura.music.ui.downloads.DownloadsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class PerfFav002ScannerAndDownloadsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var testFilesRoot: File
    private lateinit var downloadsDir: File
    private lateinit var context: Context
    private lateinit var db: AuraDatabase
    private val clientsToClose = mutableListOf<HttpClient>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication()
        testFilesRoot = File(temp.root, "files").apply { mkdirs() }
        downloadsDir = File(testFilesRoot, "downloads").apply { mkdirs() }
        val rootDir = testFilesRoot
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = rootDir
        }
        db = Room.databaseBuilder<AuraDatabase>(
            context = context,
            name = File(temp.root, "perf-fav-002.db").absolutePath,
            factory = { AuraDatabaseConstructor.initialize() }
        ).setDriver(AndroidSQLiteDriver()).build()
    }

    @After
    fun tearDown() {
        clientsToClose.forEach { runCatching { it.close() } }
        db.close()
    }

    private fun validAudioBytes(size: Int = 4096): ByteArray = ByteArray(size).apply {
        this[0] = 'I'.code.toByte()
        this[1] = 'D'.code.toByte()
        this[2] = '3'.code.toByte()
    }

    private fun writeValidPrivateAudio(fileName: String, size: Int = 4096): File {
        return File(downloadsDir, fileName).apply { writeBytes(validAudioBytes(size)) }
    }

    private fun fakeMediaStoreAudio(
        mediaStoreId: Long,
        title: String,
        artistName: String = "Local Artist",
        sizeBytes: Long = 4096L,
        dateModifiedMs: Long = 1_700_000_000_000L
    ): LocalAudioFile = LocalAudioFile(
        mediaStoreId = mediaStoreId,
        contentUri = "content://media/external/audio/media/$mediaStoreId",
        title = title,
        artistName = artistName,
        albumTitle = "Local Album",
        albumId = mediaStoreId + 1000L,
        coverUri = null,
        durationMs = 180_000L,
        mimeType = "audio/mpeg",
        fileSizeBytes = sizeBytes,
        dateModifiedEpochMs = dateModifiedMs
    )

    private fun mockApiService(
        handler: suspend (url: String) -> DownloadJobListResponseData
    ): KtorAuraApiService {
        val client = HttpClient(MockEngine { request ->
            val payload = handler(request.url.toString())
            val body = json.encodeToString(AuraResponse(data = payload))
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }) {
            install(ContentNegotiation) { json(json) }
        }
        clientsToClose.add(client)
        return KtorAuraApiService(client, "https://api.example.invalid")
    }

    private fun newAuthManager(user: String = "A"): AuthSessionManager =
        AuthSessionManager(
            store = MemorySessionStore(StoredSession(token(user), "refresh-$user", user, null)),
            signIn = { _, _ -> error("Unused in test") },
            refresh = { error("Unused in test") },
            nowSeconds = { 1000L }
        )

    @Test
    fun syncLocalMedia_consecutiveUnchangedScans_performZeroDbWritesAndZeroInvalidations() = runBlocking {
        writeValidPrivateAudio("spotify;track;p1.mp3", size = 4096)
        val localTrack = fakeMediaStoreAudio(mediaStoreId = 501L, title = "Local Song")
        var invalidateCount = 0

        val scanner = LocalMediaScanner(
            database = db,
            mediaStoreAudioDataSource = MediaStoreAudioDataSource(context),
            context = context,
            onInvalidateSearchIndex = { invalidateCount++ },
            readMediaStore = { MediaScanResult(listOf(localTrack), isComplete = true) }
        )

        val count1 = withTimeout(5_000L) { scanner.syncLocalMedia() }
        assertEquals(2, count1)
        assertEquals(1, invalidateCount)

        // Seed favorites properly (both tracks.isLiked and track_likes, as production toggleLike does)
        db.trackDao().upsertTrack(db.trackDao().getRawTrackById("spotify:track:p1")!!.copy(isLiked = true))
        db.trackDao().upsertTrack(db.trackDao().getRawTrackById("track:local:501")!!.copy(isLiked = true))
        db.trackLikeDao().insertLike(TrackLikeEntity(trackId = "spotify:track:p1", likedAt = 100L))
        db.trackLikeDao().insertLike(TrackLikeEntity(trackId = "track:local:501", likedAt = 200L))
        val p1TrackAfterScan1 = db.trackDao().getRawTrackById("spotify:track:p1")!!
        val p1LinkAfterScan1 = db.trackDao().getMediaLinkByTrackId("spotify:track:p1")!!
        val l1TrackAfterScan1 = db.trackDao().getRawTrackById("track:local:501")!!
        val l1LinkAfterScan1 = db.trackDao().getMediaLinkByTrackId("track:local:501")!!

        val likedEmissions = Collections.synchronizedList(mutableListOf<List<String>>())
        val firstEmissionReady = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.IO) {
            db.trackDao().getLikedTracksFlow().distinctUntilChanged().collect { rows ->
                likedEmissions.add(rows.map { it.id })
                firstEmissionReady.complete(Unit)
            }
        }
        try {
            withTimeout(5_000L) { firstEmissionReady.await() }
            assertEquals(1, likedEmissions.size)
            val initialEmissionCount = likedEmissions.size

            delay(50)

            // Scan 2: identical files & MediaStore snapshot -> must perform 0 DB writes
            val count2 = withTimeout(5_000L) { scanner.syncLocalMedia() }
            assertEquals(2, count2)
            assertEquals(1, invalidateCount)

            // Scan 3: repeat once more to confirm sustained idempotency
            delay(30)
            val count3 = withTimeout(5_000L) { scanner.syncLocalMedia() }
            assertEquals(2, count3)
            assertEquals(1, invalidateCount)

            val p1TrackAfterScan2 = db.trackDao().getRawTrackById("spotify:track:p1")!!
            val p1LinkAfterScan2 = db.trackDao().getMediaLinkByTrackId("spotify:track:p1")!!
            val l1TrackAfterScan2 = db.trackDao().getRawTrackById("track:local:501")!!
            val l1LinkAfterScan2 = db.trackDao().getMediaLinkByTrackId("track:local:501")!!

            assertEquals(p1TrackAfterScan1.updatedAt, p1TrackAfterScan2.updatedAt)
            assertEquals(p1LinkAfterScan1.lastScannedAt, p1LinkAfterScan2.lastScannedAt)
            assertEquals(l1TrackAfterScan1.updatedAt, l1TrackAfterScan2.updatedAt)
            assertEquals(l1LinkAfterScan1.lastScannedAt, l1LinkAfterScan2.lastScannedAt)

            delay(150)
            assertEquals(initialEmissionCount, likedEmissions.size)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun syncLocalMedia_addModifyDelete_updatesRoomWhilePreservingLikes() = runBlocking {
        val p1File = writeValidPrivateAudio("spotify;track;p1.mp3", size = 4096)
        var currentMedia = listOf(
            fakeMediaStoreAudio(mediaStoreId = 501L, title = "Keep Local"),
            fakeMediaStoreAudio(mediaStoreId = 502L, title = "Removed Local")
        )
        var invalidateCount = 0

        val scanner = LocalMediaScanner(
            database = db,
            mediaStoreAudioDataSource = MediaStoreAudioDataSource(context),
            context = context,
            onInvalidateSearchIndex = { invalidateCount++ },
            readMediaStore = { MediaScanResult(currentMedia, isComplete = true) }
        )

        assertEquals(3, withTimeout(5_000L) { scanner.syncLocalMedia() })
        assertEquals(1, invalidateCount)

        db.trackLikeDao().insertLike(TrackLikeEntity("spotify:track:p1", likedAt = 10L))
        db.trackLikeDao().insertLike(TrackLikeEntity("track:local:501", likedAt = 20L))
        db.trackLikeDao().insertLike(TrackLikeEntity("track:local:502", likedAt = 30L))
        val p1InitialSize = db.trackDao().getMediaLinkByTrackId("spotify:track:p1")!!.fileSizeBytes
        assertEquals(4096L, p1InitialSize)

        delay(30)
        p1File.writeBytes(validAudioBytes(size = 8192))
        writeValidPrivateAudio("spotify;track;p2.mp3", size = 4096)
        currentMedia = listOf(fakeMediaStoreAudio(mediaStoreId = 501L, title = "Keep Local"))

        assertEquals(3, withTimeout(5_000L) { scanner.syncLocalMedia() })
        assertEquals(2, invalidateCount)

        assertEquals(8192L, db.trackDao().getMediaLinkByTrackId("spotify:track:p1")!!.fileSizeBytes)
        assertNotNull(db.trackDao().getRawTrackById("spotify:track:p2"))
        assertNotNull(db.trackDao().getMediaLinkByTrackId("spotify:track:p2"))
        assertNull(db.trackDao().getMediaLinkByTrackId("track:local:502"))

        assertTrue(db.trackLikeDao().isTrackLiked("spotify:track:p1"))
        assertTrue(db.trackLikeDao().isTrackLiked("track:local:501"))
        assertTrue(db.trackLikeDao().isTrackLiked("track:local:502"))
    }

    @Test
    fun syncLocalMedia_rejectsCorruptAndTruncatedPrivateFiles() = runBlocking {
        File(downloadsDir, "spotify;track;tiny.mp3").writeBytes(ByteArray(100))
        File(downloadsDir, "spotify;track;html.mp3").writeBytes(
            ("<html>bad payload</html>" + " ".repeat(4096)).toByteArray().copyOf(4096)
        )
        writeValidPrivateAudio("spotify;track;valid.mp3", size = 4096)

        val scanner = LocalMediaScanner(
            database = db,
            mediaStoreAudioDataSource = MediaStoreAudioDataSource(context),
            context = context,
            onInvalidateSearchIndex = {},
            readMediaStore = { MediaScanResult(emptyList(), isComplete = true) }
        )

        val count = withTimeout(5_000L) { scanner.syncLocalMedia() }
        assertEquals(1, count)
        assertNull(db.trackDao().getRawTrackById("spotify:track:tiny"))
        assertNull(db.trackDao().getRawTrackById("spotify:track:html"))
        assertNotNull(db.trackDao().getRawTrackById("spotify:track:valid"))
    }

    @Test
    fun syncActiveJobs_alreadySucceededJobs_performZeroWritesAndZeroEmissions() = runBlocking {
        val createdIso = "2026-10-01T10:00:00Z"
        val updatedIso = "2026-10-01T10:05:00Z"
        val createdEpoch = Instant.parse(createdIso).toEpochMilli()
        val updatedEpoch = Instant.parse(updatedIso).toEpochMilli()

        db.trackDao().upsertTrack(
            TrackEntity(
                id = "track_cloud",
                primaryArtistId = null,
                albumId = null,
                title = "Cloud Ready",
                normalizedTitle = "cloud-ready",
                displayArtistName = "Artist",
                canonicalAudioSourceType = "cloud",
                isLiked = true,
                isDownloadedByAura = false,
                createdAt = 5000L,
                updatedAt = 5000L
            )
        )
        db.trackDao().upsertTrack(
            TrackEntity(
                id = "track_dl",
                primaryArtistId = null,
                albumId = null,
                title = "Offline Ready",
                normalizedTitle = "offline-ready",
                displayArtistName = "Artist",
                canonicalAudioSourceType = "downloaded",
                isLiked = true,
                isDownloadedByAura = true,
                createdAt = 6000L,
                updatedAt = 6000L
            )
        )
        db.downloadJobDao().upsert(
            listOf(
                DownloadJobEntity(
                    id = "job_1",
                    trackId = "track_cloud",
                    providerName = "ytm",
                    status = "succeeded",
                    progressPercent = 100f,
                    errorCode = null,
                    errorMessage = null,
                    attemptCount = 1,
                    createdAt = createdEpoch,
                    updatedAt = updatedEpoch
                ),
                DownloadJobEntity(
                    id = "job_2",
                    trackId = "track_dl",
                    providerName = "ytm",
                    status = "succeeded",
                    progressPercent = 100f,
                    errorCode = null,
                    errorMessage = null,
                    attemptCount = 1,
                    createdAt = createdEpoch,
                    updatedAt = updatedEpoch
                )
            )
        )

        val apiService = mockApiService {
            DownloadJobListResponseData(
                items = listOf(
                    DownloadJobResponseData(
                        id = "job_1",
                        trackId = "track_cloud",
                        providerName = "ytm",
                        status = "succeeded",
                        progressPercent = 100f,
                        createdAt = createdIso,
                        updatedAt = updatedIso,
                        operationType = "download"
                    ),
                    DownloadJobResponseData(
                        id = "job_2",
                        trackId = "track_dl",
                        providerName = "ytm",
                        status = "succeeded",
                        progressPercent = 100f,
                        createdAt = createdIso,
                        updatedAt = updatedIso,
                        operationType = "download"
                    )
                )
            )
        }
        val auth = newAuthManager("A")
        val repo = DownloadRepository(db, apiService, context) { auth }

        val successEvents = Collections.synchronizedList(mutableListOf<String>())
        val jobEmissions = Collections.synchronizedList(mutableListOf<List<DownloadJobEntity>>())
        val jobsReady = CompletableDeferred<Unit>()
        val c1 = launch(Dispatchers.IO) { repo.downloadSuccessFlow.collect { successEvents.add(it) } }
        val c2 = launch(Dispatchers.IO) {
            db.downloadJobDao().getAllJobsFlow().collect {
                jobEmissions.add(it)
                jobsReady.complete(Unit)
            }
        }
        try {
            withTimeout(5_000L) { jobsReady.await() }
            val initialJobEmissions = jobEmissions.size

            delay(30)
            withTimeout(5_000L) { repo.syncActiveJobs("Bearer ${token("A")}") }
            delay(150)

            assertEquals(5000L, db.trackDao().getRawTrackById("track_cloud")!!.updatedAt)
            val trackDl = db.trackDao().getRawTrackById("track_dl")!!
            assertEquals("downloaded", trackDl.canonicalAudioSourceType)
            assertEquals(6000L, trackDl.updatedAt)
            assertTrue(successEvents.isEmpty())
            assertEquals(initialJobEmissions, jobEmissions.size)
        } finally {
            c1.cancel()
            c2.cancel()
        }
    }

    @Test
    fun syncActiveJobs_newlySucceededAndManualReassign_emitOnceAndPreserveDownloaded() = runBlocking {
        val createdIso = "2026-10-01T10:00:00Z"
        val updatedIso = "2026-10-01T10:05:00Z"
        val createdEpoch = Instant.parse(createdIso).toEpochMilli()

        db.trackDao().upsertTracks(
            listOf(
                TrackEntity(
                    id = "track_new_ok",
                    primaryArtistId = null,
                    albumId = null,
                    title = "Transition Track",
                    normalizedTitle = "transition-track",
                    displayArtistName = "Artist",
                    canonicalAudioSourceType = "cloud_only",
                    isLiked = true,
                    isDownloadedByAura = false,
                    createdAt = 1000L,
                    updatedAt = 1000L
                ),
                TrackEntity(
                    id = "track_downloaded",
                    primaryArtistId = null,
                    albumId = null,
                    title = "Offline Track",
                    normalizedTitle = "offline-track",
                    displayArtistName = "Artist",
                    canonicalAudioSourceType = "downloaded",
                    isLiked = true,
                    isDownloadedByAura = true,
                    createdAt = 2000L,
                    updatedAt = 2000L
                ),
                TrackEntity(
                    id = "track_reassign",
                    primaryArtistId = null,
                    albumId = null,
                    title = "Reassign Track",
                    normalizedTitle = "reassign-track",
                    displayArtistName = "Artist",
                    canonicalAudioSourceType = "cloud",
                    isLiked = false,
                    isDownloadedByAura = false,
                    createdAt = 3000L,
                    updatedAt = 3000L
                )
            )
        )
        db.downloadJobDao().upsert(
            listOf(
                DownloadJobEntity(
                    id = "job_run_1",
                    trackId = "track_new_ok",
                    providerName = "ytm",
                    status = "running",
                    progressPercent = 60f,
                    errorCode = null,
                    errorMessage = null,
                    attemptCount = 1,
                    createdAt = createdEpoch,
                    updatedAt = createdEpoch
                ),
                DownloadJobEntity(
                    id = "job_run_2",
                    trackId = "track_downloaded",
                    providerName = "ytm",
                    status = "queued",
                    progressPercent = 0f,
                    errorCode = null,
                    errorMessage = null,
                    attemptCount = 1,
                    createdAt = createdEpoch,
                    updatedAt = createdEpoch
                ),
                DownloadJobEntity(
                    id = "job_reassign",
                    trackId = "track_reassign",
                    providerName = "ytm",
                    status = "succeeded",
                    progressPercent = 100f,
                    errorCode = null,
                    errorMessage = null,
                    attemptCount = 1,
                    createdAt = createdEpoch,
                    updatedAt = Instant.parse(updatedIso).toEpochMilli()
                )
            )
        )

        val apiService = mockApiService {
            DownloadJobListResponseData(
                items = listOf(
                    DownloadJobResponseData(
                        id = "job_run_1",
                        trackId = "track_new_ok",
                        providerName = "ytm",
                        status = "succeeded",
                        progressPercent = 100f,
                        createdAt = createdIso,
                        updatedAt = updatedIso,
                        operationType = "download"
                    ),
                    DownloadJobResponseData(
                        id = "job_run_2",
                        trackId = "track_downloaded",
                        providerName = "ytm",
                        status = "succeeded",
                        progressPercent = 100f,
                        createdAt = createdIso,
                        updatedAt = updatedIso,
                        operationType = "download"
                    ),
                    DownloadJobResponseData(
                        id = "job_reassign",
                        trackId = "track_reassign",
                        providerName = "ytm",
                        status = "succeeded",
                        progressPercent = 100f,
                        createdAt = createdIso,
                        updatedAt = updatedIso,
                        operationType = "manual_reassign"
                    )
                )
            )
        }
        val auth = newAuthManager("A")
        val repo = DownloadRepository(db, apiService, context) { auth }

        val successEvents = Collections.synchronizedList(mutableListOf<String>())
        val reassignedEvents = Collections.synchronizedList(mutableListOf<String>())
        val c1 = launch(Dispatchers.IO) { repo.downloadSuccessFlow.collect { successEvents.add(it) } }
        val c2 = launch(Dispatchers.IO) { repo.audioVersionReassignedFlow.collect { reassignedEvents.add(it) } }
        try {
            withTimeout(5_000L) {
                repo.syncActiveJobs("Bearer ${token("A")}")
                while (successEvents.size < 3 || reassignedEvents.isEmpty()) {
                    delay(20)
                }
            }
            assertEquals(listOf("track_new_ok", "track_downloaded", "track_reassign"), successEvents)
            assertEquals(listOf("track_reassign"), reassignedEvents)
            assertEquals("cloud", db.trackDao().getRawTrackById("track_new_ok")!!.canonicalAudioSourceType)
            assertEquals("downloaded", db.trackDao().getRawTrackById("track_downloaded")!!.canonicalAudioSourceType)
            assertEquals(2000L, db.trackDao().getRawTrackById("track_downloaded")!!.updatedAt)
        } finally {
            c1.cancel()
            c2.cancel()
        }
    }

    @Test
    fun syncActiveJobs_concurrentLogoutOrAccountSwitch_abortsBeforeAnyWrite() = runBlocking {
        val enteredLogout = CompletableDeferred<Unit>()
        val releaseLogout = CompletableDeferred<Unit>()
        val apiLogout = mockApiService {
            enteredLogout.complete(Unit)
            releaseLogout.await()
            DownloadJobListResponseData(
                items = listOf(
                    DownloadJobResponseData(
                        id = "job_orphan_1",
                        trackId = "track_orphan_1",
                        providerName = "ytm",
                        status = "succeeded",
                        progressPercent = 100f,
                        createdAt = "2026-10-01T10:00:00Z",
                        updatedAt = "2026-10-01T10:05:00Z"
                    )
                )
            )
        }
        val authLogout = newAuthManager("A")
        val repoLogout = DownloadRepository(db, apiLogout, context) { authLogout }
        val emissions = Collections.synchronizedList(mutableListOf<String>())
        val c1 = launch(Dispatchers.IO) { repoLogout.downloadSuccessFlow.collect { emissions.add(it) } }
        try {
            val syncJob = async { repoLogout.syncActiveJobs("Bearer ${token("A")}") }
            withTimeout(5_000L) { enteredLogout.await() }
            authLogout.logout()
            releaseLogout.complete(Unit)
            withTimeout(5_000L) { syncJob.await() }
            delay(100)
            assertTrue(db.downloadJobDao().getAllJobsFlow().first().isEmpty())
            assertNull(db.trackDao().getRawTrackById("track_orphan_1"))
            assertTrue(emissions.isEmpty())

            val enteredSwitch = CompletableDeferred<Unit>()
            val releaseSwitch = CompletableDeferred<Unit>()
            val apiSwitch = mockApiService {
                enteredSwitch.complete(Unit)
                releaseSwitch.await()
                DownloadJobListResponseData(
                    items = listOf(
                        DownloadJobResponseData(
                            id = "job_orphan_2",
                            trackId = "track_orphan_2",
                            providerName = "ytm",
                            status = "succeeded",
                            progressPercent = 100f,
                            createdAt = "2026-10-01T10:00:00Z",
                            updatedAt = "2026-10-01T10:05:00Z"
                        )
                    )
                )
            }
            val authSwitch = newAuthManager("A")
            val repoSwitch = DownloadRepository(db, apiSwitch, context) { authSwitch }
            val switchJob = async { repoSwitch.syncActiveJobs("Bearer ${token("A")}") }
            withTimeout(5_000L) { enteredSwitch.await() }
            authSwitch.logout()
            authSwitch.saveSession(token("B"), "refresh-B", "B", null)
            releaseSwitch.complete(Unit)
            withTimeout(5_000L) { switchJob.await() }
            delay(100)
            assertTrue(db.downloadJobDao().getAllJobsFlow().first().isEmpty())
            assertNull(db.trackDao().getRawTrackById("track_orphan_2"))
            assertTrue(emissions.isEmpty())
        } finally {
            c1.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun downloadsViewModel_evaluatesTokenProviderOffMainThread() = runBlocking {
        val mainDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-main-ui")
        }.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        try {
            val mainThread = withTimeout(5_000L) {
                withContext(mainDispatcher) { Thread.currentThread() }
            }
            val apiCalled = CompletableDeferred<Unit>()
            val tokenEvaluated = CompletableDeferred<Unit>()
            var observedTokenThread: Thread? = null

            val apiService = mockApiService {
                apiCalled.complete(Unit)
                DownloadJobListResponseData(items = emptyList())
            }
            val auth = newAuthManager("A")
            val repo = DownloadRepository(db, apiService, context) { auth }

            withTimeout(5_000L) {
                withContext(mainDispatcher) {
                    DownloadsViewModel(
                        downloadRepository = repo,
                        tokenProvider = {
                            observedTokenThread = Thread.currentThread()
                            tokenEvaluated.complete(Unit)
                            "Bearer ${token("A")}"
                        }
                    )
                }
            }

            withTimeout(5_000L) {
                tokenEvaluated.await()
                apiCalled.await()
            }
            delay(50)
            assertNotNull(observedTokenThread)
            assertNotEquals(mainThread, observedTokenThread)
            assertNotEquals("test-main-ui", observedTokenThread!!.name)
        } finally {
            Dispatchers.resetMain()
            mainDispatcher.close()
        }
    }
}
