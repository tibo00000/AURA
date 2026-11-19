package com.aura.music.data.repository

import android.app.Application
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.aura.music.data.local.*
import com.aura.music.data.network.*
import com.aura.music.data.playlist.PlaylistSnapshotReconciler
import java.io.File
import java.time.Instant
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
@Config(sdk=[34], application=Application::class, manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LegacyTrackParentRecoveryTest {
    @get:Rule val temp=TemporaryFolder()
    private fun open(file: File) = Room.databaseBuilder<AuraDatabase>(RuntimeEnvironment.getApplication(),file.absolutePath,
        factory={ AuraDatabaseConstructor.initialize() }).setDriver(AndroidSQLiteDriver()).build()

    @Test fun snapshotRepairsLegacyMissingParentsAndKeepsAudioLikesEditsOutboxAndOccurrences() = runBlocking {
        val file=temp.newFile("legacy-parents.db")
        val original=TrackEntity("track:local:1","principal",null,"My edited title","my edited title","SDM",
            displayAlbumTitle="My edited album",durationMs=179000,coverUri="file:keep-cover",
            canonicalAudioSourceType="local",isLiked=true,isDownloadedByAura=false,createdAt=1,updatedAt=2)
        val media=TrackMediaLinkEntity("media",original.id,42,"content://media/external/audio/media/42",availabilityStatus="available",lastScannedAt=1)
        val items=listOf(PlaylistItemEntity("a","playlist",original.id,0,1),PlaylistItemEntity("b","playlist",original.id,1,1))
        val op=SyncOutboxEntity("pending-like","track_like",original.id,"create","{}","pending",createdAt=1,updatedAt=1)
        open(file).let { db ->
            db.artistDao().insertArtistsIgnore(listOf(ArtistEntity("principal","SDM","sdm",pictureUri="file:artist",createdAt=1,updatedAt=1)))
            db.trackDao().upsertTrack(original)
            db.trackDao().upsertTrackMediaLinks(listOf(media))
            db.trackLikeDao().insertLike(TrackLikeEntity(original.id,1))
            db.playlistDao().insertPlaylist(PlaylistEntity("playlist","Titi",isPinned=false,createdAt=1,updatedAt=2))
            items.forEach { db.playlistDao().insertPlaylistItem(it) }
            db.syncOutboxDao().insert(op)
            db.close()
        }
        // Faithful legacy fixture: references predate enforcement; never disable FK in the fix.
        AndroidSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.execSQL("PRAGMA foreign_keys=OFF")
            connection.execSQL("UPDATE tracks SET primary_artist_id='missing-artist',album_id='missing-album'")
        }
        val stamp=Instant.ofEpochMilli(1).toString()
        val metadata=PlaylistTrackMetadata("deezer","19","Provider title","SDM, Josman",album="Provider album",
            durationMs=180000,artists=listOf(ArtistCreditDto("principal","SDM"),ArtistCreditDto("guest","Josman")))
        val snapshot=PlaylistSnapshotResponse("A",1,2,listOf(PlaylistSnapshotEntry("playlist","A","Titi",isPinned=false,
            createdAt=stamp,updatedAt=Instant.ofEpochMilli(3).toString(),orderToken="order",items=items.map {
                PlaylistItemResponse(it.id,it.playlistId,it.trackId,it.position,stamp,sourceMetadata=metadata)
            },itemCount=2)),1,1)
        open(file).let { db ->
            try {
                val reconciler=PlaylistSnapshotReconciler(db) { Instant.parse(it).toEpochMilli() }
                reconciler.apply(snapshot,"A",{true})
                val after=db.trackDao().getRawTrackById(original.id)!!
                assertEquals(original.title,after.title)
                assertEquals(original.displayAlbumTitle,after.displayAlbumTitle)
                assertEquals(original.coverUri,after.coverUri)
                assertEquals(original.durationMs,after.durationMs)
                assertEquals("missing-album",after.albumId)
                assertTrue(after.isLiked)
                assertEquals(media.contentUri,db.trackDao().getTrackContentUri(original.id))
                assertEquals(items,db.playlistDao().getPlaylistItems("playlist"))
                assertEquals(listOf(op),db.syncOutboxDao().getPendingOperations())
                assertEquals(1,db.trackDao().getTrackCount())
                assertEquals(listOf(original.id),db.trackDao().getTracksForArtist("guest").map { it.id })
                val artists=db.trackArtistDao().getArtistIdentities()
                assertTrue(artists.any { it.id=="missing-artist" })
                assertEquals("file:artist",artists.first { it.id=="principal" }.pictureUri)
                val raw=after
                reconciler.apply(snapshot,"A",{true})
                assertEquals(raw,db.trackDao().getRawTrackById(original.id))
                assertEquals(artists,db.trackArtistDao().getArtistIdentities())
            } finally { db.close() }
        }
        AndroidSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.prepare("PRAGMA foreign_key_check").use { statement -> assertFalse(statement.step()) }
        }
    }
    @Test fun sessionChangeRollsBackParentRecoveryWithoutDroppingLegacyReferences() = runBlocking {
        val file=temp.newFile("cancelled-parents.db")
        open(file).let { db ->
            db.artistDao().insertArtistsIgnore(listOf(ArtistEntity("principal","SDM","sdm",createdAt=1,updatedAt=1)))
            db.trackDao().upsertTrack(TrackEntity("track","principal",null,"Keep","keep","SDM",
                canonicalAudioSourceType="local",isLiked=true,isDownloadedByAura=false,createdAt=1,updatedAt=1))
            db.close()
        }
        AndroidSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.execSQL("PRAGMA foreign_keys=OFF")
            connection.execSQL("UPDATE tracks SET primary_artist_id='missing-artist',album_id='missing-album'")
        }
        open(file).let { db ->
            try {
                val before=db.trackDao().getRawTrackById("track")
                var checks=0
                var cancelled=false
                try {
                    db.useWriterConnection { writer -> writer.immediateTransaction {
                        com.aura.music.data.metadata.repairMissingTrackParents(db,2) { ++checks<3 }
                    } }
                } catch (_: kotlinx.coroutines.CancellationException) { cancelled=true }
                assertTrue(cancelled)
                assertEquals(before,db.trackDao().getRawTrackById("track"))
                assertEquals(listOf("principal"),db.trackArtistDao().getArtistIdentities().map { it.id })
            } finally { db.close() }
        }
    }

}
