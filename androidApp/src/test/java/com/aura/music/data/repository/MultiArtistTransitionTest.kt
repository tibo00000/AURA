package com.aura.music.data.repository

import android.app.Application
import androidx.room3.Room
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.aura.music.data.local.*
import com.aura.music.data.metadata.prepareArtistCredits
import com.aura.music.data.metadata.AudioMetadataEditor
import com.aura.music.data.network.ArtistCreditDto
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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
class MultiArtistTransitionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun open(file: File) = Room.databaseBuilder<AuraDatabase>(
        RuntimeEnvironment.getApplication(), file.absolutePath, factory = { AuraDatabaseConstructor.initialize() },
    ).setDriver(AndroidSQLiteDriver()).addMigrations(AuraDatabase.MIGRATION_9_10).build()
    private fun track() = TrackEntity("track:local:1", "principal", null, "Manual title", "manual title", "SDM",
        canonicalAudioSourceType = "local", isLiked = true, isDownloadedByAura = false, createdAt = 1, updatedAt = 2)
    private val credits = listOf(ArtistCreditDto("principal", "SDM"), ArtistCreditDto("guest", "Josman"))

    @Test fun versionNineMigrationPreservesAudioLikesRepeatedOccurrencesAndPendingOutbox() = runBlocking {
        val file = temp.newFile("migration.db")
        val original = track()
        val media = TrackMediaLinkEntity("media", original.id, 42, "content://media/external/audio/media/42",
            availabilityStatus = "available", lastScannedAt = 3)
        val likes = TrackLikeEntity(original.id, 4)
        val occurrences = listOf(PlaylistItemEntity("a", "playlist", original.id, 0, 5), PlaylistItemEntity("b", "playlist", original.id, 1, 6))
        val op = SyncOutboxEntity("op", "playlist_item", "b", "create", "{}", "pending", createdAt = 7, updatedAt = 7)
        open(file).let { db ->
            db.artistDao().insertArtistsIgnore(listOf(ArtistEntity("principal", "SDM", "sdm", createdAt=1, updatedAt=1)))
            db.trackDao().upsertTrack(original)
            db.trackDao().upsertTrackMediaLinks(listOf(media))
            db.trackLikeDao().insertLike(likes)
            db.playlistDao().insertPlaylist(PlaylistEntity("playlist", "Keep", isPinned=false, createdAt=1, updatedAt=1))
            occurrences.forEach { db.playlistDao().insertPlaylistItem(it) }
            db.syncOutboxDao().insert(op)
            db.close()
        }
        AndroidSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.execSQL("DROP TABLE track_artists")
            connection.execSQL("DROP TABLE metadata_transition_state")
            connection.execSQL("DROP TABLE metadata_transition_runs")
            connection.execSQL("PRAGMA user_version = 9")
        }
        open(file).let { db ->
            try {
                assertEquals(original, db.trackDao().getRawTrackById(original.id))
                assertEquals(media.contentUri, db.trackDao().getTrackContentUri(original.id))
                assertEquals(occurrences, db.playlistDao().getPlaylistItems("playlist"))
                assertEquals(listOf(op), db.syncOutboxDao().getPendingOperations())
                assertEquals(listOf(TrackArtistEntity(original.id,"principal",0,"primary","legacy")),db.trackArtistDao().getCredits(original.id))
                assertEquals(1,db.trackDao().getTracksForArtist("principal").size)
            } finally { db.close() }
        }
    }

    @Test fun guestBrowseIsUniqueRepeatDoesNotWriteAndTitleOnlyEditKeepsCredits() = runBlocking {
        val db = open(temp.newFile("credits.db"))
        try {
            db.artistDao().insertArtistsIgnore(listOf(ArtistEntity("principal", "SDM", "sdm", createdAt=1, updatedAt=1)))
            db.trackDao().upsertTrack(track())
            suspend fun enrich(current: () -> Boolean = { true }) {
                val raw = db.trackDao().getRawTrackById(track().id)!!
                val plan = prepareArtistCredits(db, mapOf(raw.id to raw), mapOf(raw.id to credits), 10)
                db.useWriterConnection { writer -> writer.immediateTransaction { plan.apply(db,current) } }
            }
            enrich { false }
            assertTrue(db.trackArtistDao().getCredits(track().id).isEmpty())
            enrich()
            assertEquals("SDM, Josman", db.trackDao().getRawTrackById(track().id)?.displayArtistName)
            assertEquals(listOf(track().id), db.trackDao().getTracksForArtist("guest").map { it.id })
            assertEquals(1, db.artistDao().getAllBrowseArtists().first { it.id=="guest" }.trackCount)
            val before = db.trackDao().getRawTrackById(track().id)
            val beforeCredits = db.trackArtistDao().getCredits(track().id)
            val plan = prepareArtistCredits(db,mapOf(before!!.id to before),mapOf(before.id to credits),20)
            assertFalse(plan.hasChanges)
            enrich()
            assertEquals(before, db.trackDao().getRawTrackById(before.id))
            val result = AudioMetadataEditor(db, RuntimeEnvironment.getApplication()) {}.updateTrackMetadata(before.id,"New title",before.displayArtistName,null)
            assertTrue(result.isSuccess)
            assertEquals(beforeCredits,db.trackArtistDao().getCredits(before.id))
            assertEquals("New title",db.trackDao().getRawTrackById(before.id)?.title)
            val stale = prepareArtistCredits(db,mapOf(before.id to db.trackDao().getRawTrackById(before.id)!!),mapOf(before.id to credits.reversed()),30)
            db.trackDao().upsertTrack(db.trackDao().getRawTrackById(before.id)!!.copy(displayArtistName="My artist"))
            db.useWriterConnection { writer -> writer.immediateTransaction { stale.apply(db) { true } } }
            assertEquals("My artist",db.trackDao().getRawTrackById(before.id)?.displayArtistName)
            assertEquals(beforeCredits,db.trackArtistDao().getCredits(before.id))
            val dao = db.metadataTransitionDao()
            dao.beginRun(MetadataTransitionRunEntity("A",1))
            dao.seed(listOf(MetadataTransitionStateEntity("A",before.id,1,"pending")))
            dao.save(MetadataTransitionStateEntity("A",before.id,1,"resolved"))
            dao.seed(listOf(MetadataTransitionStateEntity("A",before.id,1,"pending")))
            assertTrue(dao.pending("A",1).isEmpty())
            assertEquals(0,dao.hasRun("B",1))
        } finally { db.close() }
    }
    @Test fun transitionIsOncePerAccountAndCannotPublishAfterAccountChange() = runBlocking {
        val db = open(temp.newFile("account.db"))
        try {
            db.artistDao().insertArtistsIgnore(listOf(ArtistEntity("principal", "SDM", "sdm", createdAt=1, updatedAt=1)))
            db.trackDao().upsertTrack(track())
            val auth = com.aura.music.core.manager(com.aura.music.core.MemorySessionStore(
                com.aura.music.core.StoredSession(com.aura.music.core.token("A"), null, "A", null))) { error("Unexpected renewal") }
            val owner = auth.captureScope()
            var calls=0
            var switchAccount=true
            val response = com.aura.music.data.network.AuraResponse(data = com.aura.music.data.network.SyncedFileListResponseData(
                listOf(com.aura.music.data.network.SyncedFileResponseData(track().id, artists=credits, albumId="album-transition", albumTitle="Transition",
                    metadataTransitionVersion=1, metadataTransitionStatus="resolved"))))
            val api = java.lang.reflect.Proxy.newProxyInstance(com.aura.music.data.network.AuraApiService::class.java.classLoader,
                arrayOf<Class<*>>(com.aura.music.data.network.AuraApiService::class.java)) { _, method, _ ->
                check(method.name=="listSyncFiles") { "Unexpected request ${method.name}" }
                calls++
                if(switchAccount) auth.logout()
                response
            } as com.aura.music.data.network.AuraApiService
            val coordinator = com.aura.music.data.metadata.MetadataTransitionCoordinator(db,auth,api,
                com.aura.music.data.playlist.PlaylistSourceReader({ error("Unexpected provider access") })) {}
            coordinator.run(owner)
            assertTrue(db.trackArtistDao().getCredits(track().id).isEmpty())
            assertEquals("SDM",db.trackDao().getRawTrackById(track().id)?.displayArtistName)
            auth.saveSession(com.aura.music.core.token("A"),null,"A",null)
            switchAccount=false
            coordinator.run(auth.captureScope())
            assertEquals(2,calls)
            assertEquals("album-transition",db.trackDao().getRawTrackById(track().id)?.albumId)
            assertEquals(listOf("principal","guest"),db.trackArtistDao().getCredits(track().id).map { it.artistId })
            coordinator.run(auth.captureScope())
            assertEquals(2,calls)
        } finally { db.close() }
    }

}

