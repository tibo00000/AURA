package com.aura.music.ui.screens

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.AuraDatabaseConstructor
import com.aura.music.data.local.TrackEntity
import com.aura.music.data.local.TrackLikeEntity
import com.aura.music.data.local.TrackListRow
import com.aura.music.data.local.TrackMediaLinkEntity
import com.aura.music.data.media.MediaStoreAudioDataSource
import com.aura.music.data.network.KtorAuraApiService
import com.aura.music.data.network.SyncedFileResponseData
import com.aura.music.data.repository.LocalLibraryRepository
import com.aura.music.data.repository.SyncRepository
import com.aura.music.ui.utils.TrackLookupIndex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.SQLiteMode
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FavoritesScreenPerfTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AuraDatabase
    private lateinit var dbFile: File
    private lateinit var httpClient: HttpClient
    private lateinit var repository: LocalLibraryRepository
    private val nextMediaStoreId = AtomicLong(-100L)

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val files = File(temp.root, "files").apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = files
        }
        dbFile = File(temp.root, "favorites-perf-test.db")
        db = Room.databaseBuilder<AuraDatabase>(
            context = context,
            name = dbFile.absolutePath,
            factory = { AuraDatabaseConstructor.initialize() }
        ).setDriver(AndroidSQLiteDriver()).build()

        httpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val apiService = KtorAuraApiService(httpClient, "https://api.example.invalid")
        val syncRepo = SyncRepository(db, apiService, context)
        repository = LocalLibraryRepository(
            database = db,
            mediaStoreAudioDataSource = MediaStoreAudioDataSource(context),
            syncRepositoryProvider = { syncRepo },
            apiService = apiService,
            context = context
        )
    }

    @After
    fun tearDown() {
        httpClient.close()
        db.close()
    }

    private suspend fun insertTrack(
        id: String,
        title: String,
        artist: String = "Artist $id",
        isLiked: Boolean = false,
        likedAt: Long = 100L,
        contentUri: String? = null
    ) {
        db.trackDao().upsertTrack(
            TrackEntity(
                id = id,
                primaryArtistId = null,
                albumId = null,
                title = title,
                normalizedTitle = title.lowercase(),
                displayArtistName = artist,
                canonicalAudioSourceType = if (contentUri != null) "downloaded" else "cloud",
                isLiked = isLiked,
                isDownloadedByAura = contentUri != null,
                createdAt = 10L,
                updatedAt = 10L
            )
        )
        if (isLiked) {
            db.trackLikeDao().insertLike(TrackLikeEntity(trackId = id, likedAt = likedAt))
        }
        if (contentUri != null) {
            db.trackDao().upsertTrackMediaLinks(
                listOf(
                    TrackMediaLinkEntity(
                        id = "media:$id",
                        trackId = id,
                        mediaStoreId = nextMediaStoreId.decrementAndGet(),
                        contentUri = contentUri,
                        fileSizeBytes = 2048L,
                        mimeType = "audio/mpeg",
                        dateModifiedEpochMs = 100L,
                        availabilityStatus = "present",
                        lastScannedAt = 100L
                    )
                )
            )
        }
    }

    @Test
    fun observeLikedTracks_deduplicatesIdenticalReemissions_andPropagatesRealChanges() = runBlocking {
        insertTrack(id = "t1", title = "Song One", isLiked = true, likedAt = 200L)
        insertTrack(id = "t2", title = "Song Two", isLiked = true, likedAt = 100L)
        insertTrack(id = "unliked", title = "Other Song", isLiked = false)

        val emissions = mutableListOf<List<TrackListRow>>()
        val collector = launch(Dispatchers.IO) {
            repository.observeLikedTracks().collect { list ->
                synchronized(emissions) { emissions.add(list) }
            }
        }

        try {
            // Attend l'émission initiale
            withTimeoutCondition { synchronized(emissions) { emissions.size >= 1 } }
            assertEquals(listOf("t1", "t2"), emissions.last().map { it.id })
            val initialCount = synchronized(emissions) { emissions.size }

            // 1. Mutation sur une table surveillée (tracks/track_media_links) qui ne touche PAS aux favoris
            insertTrack(id = "unliked_2", title = "Unrelated", isLiked = false, contentUri = "file:///tmp/u2.mp3")
            insertTrack(id = "unliked", title = "Other Song Updated", isLiked = false)
            delay(250)
            assertEquals(
                "Une invalidation SQLite sans modification des favoris ne doit pas réémettre",
                initialCount,
                synchronized(emissions) { emissions.size }
            )

            // 2. Changement de disponibilité média (contentUri) sur un favori -> doit émettre immédiatement
            val countBeforeMedia = synchronized(emissions) { emissions.size }
            db.trackDao().upsertTrackMediaLinks(
                listOf(
                    TrackMediaLinkEntity(
                        id = "media:t2",
                        trackId = "t2",
                        mediaStoreId = nextMediaStoreId.decrementAndGet(),
                        contentUri = "file:///downloads/t2.mp3",
                        fileSizeBytes = 4096L,
                        mimeType = "audio/mpeg",
                        dateModifiedEpochMs = 300L,
                        availabilityStatus = "present",
                        lastScannedAt = 300L
                    )
                )
            )
            withTimeoutCondition { synchronized(emissions) { emissions.size > countBeforeMedia } }
            val t2AfterMedia = emissions.last().first { it.id == "t2" }
            assertEquals("file:///downloads/t2.mp3", t2AfterMedia.contentUri)

            // 3. Changement de métadonnées sur un favori -> doit émettre immédiatement
            val countBeforeMeta = synchronized(emissions) { emissions.size }
            val existingT1 = db.trackDao().getRawTrackById("t1")!!
            db.trackDao().upsertTrack(existingT1.copy(title = "Song One (Remastered)", updatedAt = 500L))
            withTimeoutCondition { synchronized(emissions) { emissions.size > countBeforeMeta } }
            assertEquals("Song One (Remastered)", emissions.last().first { it.id == "t1" }.title)

            // 4. Suppression d'un favori -> doit émettre immédiatement
            val countBeforeUnlike = synchronized(emissions) { emissions.size }
            db.trackLikeDao().deleteLike("t1")
            withTimeoutCondition { synchronized(emissions) { emissions.size > countBeforeUnlike } }
            assertEquals(listOf("t2"), emissions.last().map { it.id })

            // 5. Ajout d'un nouveau favori -> doit émettre immédiatement
            val countBeforeLike = synchronized(emissions) { emissions.size }
            insertTrack(id = "t3", title = "Song Three", isLiked = true, likedAt = 600L)
            withTimeoutCondition { synchronized(emissions) { emissions.size > countBeforeLike } }
            assertEquals(listOf("t3", "t2"), emissions.last().map { it.id })
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun buildFavoritesPlayTrackEvent_tracksMutationsSortFilterInsertAndDeleteAccurately() {
        fun row(id: String, title: String) = TrackListRow(
            id = id,
            artistId = "art_$id",
            albumId = "alb_$id",
            title = title,
            artistName = "Artist $id",
            albumTitle = "Album $id",
            contentUri = null,
            durationMs = 180_000L,
            coverUri = null,
            isLiked = true
        )

        val t1 = row("t1", "Alpha")
        val t2 = row("t2", "Beta")
        val t3 = row("t3", "Gamma")
        val t0 = row("t0", "Zero")

        // Liste initiale
        val initialList = listOf(t1, t2, t3)
        val playInitial = buildFavoritesPlayTrackEvent(initialList, "t2")
        assertNotNull(playInitial)
        assertEquals("t2", playInitial!!.trackId)
        assertEquals(1, playInitial.startIndex)
        assertEquals(listOf("t1", "t2", "t3"), playInitial.contextTracks.map { it.trackId })

        // Après tri décroissant (Gamma, Beta, Alpha)
        val sortedDesc = initialList.sortedByDescending { it.title }
        val playAfterSort = buildFavoritesPlayTrackEvent(sortedDesc, "t1")
        assertNotNull(playAfterSort)
        assertEquals("t1", playAfterSort!!.trackId)
        assertEquals(2, playAfterSort.startIndex)
        assertEquals(listOf("t3", "t2", "t1"), playAfterSort.contextTracks.map { it.trackId })

        // Après insertion en tête
        val afterInsert = listOf(t0) + initialList
        val playAfterInsert = buildFavoritesPlayTrackEvent(afterInsert, "t2")
        assertNotNull(playAfterInsert)
        assertEquals("t2", playAfterInsert!!.trackId)
        assertEquals(2, playAfterInsert.startIndex)
        assertEquals(listOf("t0", "t1", "t2", "t3"), playAfterInsert.contextTracks.map { it.trackId })

        // Après suppression d'un élément précédent (t1 supprimé)
        val afterDelete = listOf(t0, t2, t3)
        val playAfterDelete = buildFavoritesPlayTrackEvent(afterDelete, "t2")
        assertNotNull(playAfterDelete)
        assertEquals("t2", playAfterDelete!!.trackId)
        assertEquals(1, playAfterDelete.startIndex)
        assertEquals(listOf("t0", "t2", "t3"), playAfterDelete.contextTracks.map { it.trackId })

        // Après filtrage excluant la piste ciblée
        val filtered = afterDelete.filter { it.id == "t3" }
        assertEquals(0, buildFavoritesPlayTrackEvent(filtered, "t3")!!.startIndex)
        assertNull(buildFavoritesPlayTrackEvent(filtered, "t2"))
        assertNull(buildFavoritesPlayTrackEvent(emptyList(), "t2"))
    }

    @Test
    fun trackLookupIndex_buildAsync_executesOffCallerThread_supportsCancellation_andMatchesSync() = runBlocking {
        val cloudFiles = (1..250).map { i ->
            SyncedFileResponseData(
                trackId = "track:cloud:$i",
                title = "Track $i",
                artistName = "Artist $i",
                albumTitle = "Album $i"
            )
        }
        val syncedIds = cloudFiles.map { it.trackId }.toSet()

        val observedThreadNames = mutableListOf<String>()
        val countingDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.Default.dispatch(context) {
                    observedThreadNames.add(Thread.currentThread().name)
                    block.run()
                }
            }
        }

        val callerThread = Thread.currentThread().name
        val syncBuilt = TrackLookupIndex.build(
            allLibraryTracks = emptyList(),
            cloudFiles = cloudFiles,
            syncedCloudTrackIds = syncedIds
        )
        val asyncBuilt = TrackLookupIndex.buildAsync(
            allLibraryTracks = emptyList(),
            cloudFiles = cloudFiles,
            syncedCloudTrackIds = syncedIds,
            dispatcher = countingDispatcher
        )

        assertTrue("buildAsync doit s'exécuter sur le dispatcher de travail", observedThreadNames.isNotEmpty())
        assertTrue(
            "Le thread de travail ne doit pas être le thread appelant ($callerThread)",
            observedThreadNames.all { it != callerThread }
        )

        for (i in 1..250 step 25) {
            assertEquals(
                syncBuilt.isCloudSynced("track:cloud:$i", "Track $i", "Artist $i", "Album $i"),
                asyncBuilt.isCloudSynced("track:cloud:$i", "Track $i", "Artist $i", "Album $i")
            )
        }
        assertFalse(asyncBuilt.isCloudSynced("missing", "Unknown", "Artist", "Album"))

        // Vérifie que buildCloudOnly donne exactement le même résultat sans pistes locales
        val cloudOnly = TrackLookupIndex.buildCloudOnly(cloudFiles, syncedIds)
        assertTrue(cloudOnly.isCloudSynced("track:cloud:42", "Track 42", "Artist 42", "Album 42"))
        assertNull(cloudOnly.findLocalMatch("track:cloud:42", "Track 42", "Artist 42", "Album 42"))

        // Vérifie l'annulation propre d'un calcul obsolète et l'absence de cache global entre instances
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val gatedDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.Default.dispatch(context) {
                    started.complete(Unit)
                    runBlocking { gate.await() }
                    block.run()
                }
            }
        }

        val obsoleteJob = async {
            TrackLookupIndex.buildAsync(
                allLibraryTracks = emptyList(),
                cloudFiles = cloudFiles,
                syncedCloudTrackIds = syncedIds,
                dispatcher = gatedDispatcher
            )
        }
        started.await()
        obsoleteJob.cancel(CancellationException("Superseded by newer cloud snapshot"))
        gate.complete(Unit)

        var caughtCancellation = false
        try {
            obsoleteJob.await()
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue("Le calcul obsolète annulé doit se terminer sur CancellationException", caughtCancellation)

        // Aucun état résiduel entre appels (isolation utilisateur)
        val freshAccountIndex = TrackLookupIndex.buildAsync(
            allLibraryTracks = emptyList(),
            cloudFiles = emptyList(),
            syncedCloudTrackIds = emptySet(),
            dispatcher = Dispatchers.Default
        )
        assertFalse(freshAccountIndex.isCloudSynced("track:cloud:1", "Track 1", "Artist 1", "Album 1"))
    }

    private suspend fun withTimeoutCondition(
        timeoutMs: Long = 3_000L,
        predicate: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Condition non satisfaite avant ${timeoutMs}ms")
            }
            delay(20)
        }
    }
}
