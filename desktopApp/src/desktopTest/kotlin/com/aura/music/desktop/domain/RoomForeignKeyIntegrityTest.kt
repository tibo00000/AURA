package com.aura.music.desktop.domain

import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.local.DownloadJobEntity
import com.aura.music.data.local.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Test vérifiant l'intégrité des clés étrangères SQLite / Room :
 * 1. download_jobs.track_id référence tracks.id
 * 2. tracks.primary_artist_id référence artists.id (doit être null pour un placeholder)
 * 3. tracks.album_id référence albums.id (doit être null pour un placeholder)
 * 4. La stratégie de placeholder utilisée dans DownloadRepository et DesktopDownloadManager
 *    permet l'insertion sans violation de contrainte FK.
 */
class RoomForeignKeyIntegrityTest {

    private lateinit var database: AuraDatabase
    private lateinit var tempDbFile: File

    @Before
    fun setUp() {
        tempDbFile = File.createTempFile("aura_fk_test_db_", ".db")
        database = AuraDatabase.getInstance(tempDbFile.absolutePath)
    }

    @After
    fun tearDown() {
        if (tempDbFile.exists()) {
            tempDbFile.delete()
        }
    }

    @Test
    fun testDownloadJobWithoutTrackEntityFailsForeignKey() = runBlocking {
        val orphanJob = DownloadJobEntity(
            id = "job_orphan_1",
            trackId = "track_non_existent",
            providerName = "ytm",
            status = "queued",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        try {
            database.downloadJobDao().upsert(listOf(orphanJob))
            fail("Inserting download job without a parent track entity should fail with foreign key violation")
        } catch (e: Exception) {
            // Succès du test : une exception de contrainte de clé étrangère SQLite doit être levée
            val message = e.message ?: ""
            assertTrue(
                "Expected foreign key constraint violation, got: $message",
                message.contains("FOREIGN KEY", ignoreCase = true) ||
                message.contains("constraint failed", ignoreCase = true) ||
                e.javaClass.simpleName.contains("Constraint", ignoreCase = true)
            )
        }
    }

    @Test
    fun testTrackPlaceholderWithNonExistentArtistFailsForeignKey() = runBlocking {
        val trackWithBogusArtist = TrackEntity(
            id = "track_with_bad_artist",
            primaryArtistId = "artist_does_not_exist",
            albumId = null,
            title = "Test Title",
            normalizedTitle = "test title",
            displayArtistName = "Unknown",
            canonicalAudioSourceType = "cloud_only",
            isLiked = false,
            isDownloadedByAura = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        try {
            database.trackDao().upsertTracks(listOf(trackWithBogusArtist))
            fail("Inserting track placeholder with non-null non-existent artist should fail foreign key constraint")
        } catch (e: Exception) {
            val message = e.message ?: ""
            assertTrue(
                "Expected foreign key constraint violation on primaryArtistId, got: $message",
                message.contains("FOREIGN KEY", ignoreCase = true) ||
                message.contains("constraint failed", ignoreCase = true) ||
                e.javaClass.simpleName.contains("Constraint", ignoreCase = true)
            )
        }
    }

    @Test
    fun testPlaceholderStrategySucceedsAndAllowsDownloadJobInsertion() = runBlocking {
        val now = System.currentTimeMillis()
        val trackId = "deezer:12345678"
        val jobId = "job_reassign_123"

        // 1. Placeholder avec primaryArtistId = null et albumId = null
        val placeholderTrack = TrackEntity(
            id = trackId,
            primaryArtistId = null,
            albumId = null,
            title = "Bohemian Rhapsody",
            normalizedTitle = "bohemian rhapsody",
            displayArtistName = "Queen",
            displayAlbumTitle = "A Night at the Opera",
            durationMs = 0L,
            coverUri = "https://example.com/cover.jpg",
            canonicalAudioSourceType = "cloud_only",
            isLiked = false,
            isDownloadedByAura = false,
            createdAt = now,
            updatedAt = now
        )

        // Insertion du placeholder : doit réussir sans violation
        database.trackDao().upsertTracks(listOf(placeholderTrack))

        val retrievedTrack = database.trackDao().getRawTrackById(trackId)
        assertNotNull("Retrieved track placeholder must not be null", retrievedTrack)
        assertEquals("Queen", retrievedTrack?.displayArtistName)

        // 2. Insertion du DownloadJobEntity référençant ce trackId
        val job = DownloadJobEntity(
            id = jobId,
            trackId = trackId,
            providerName = "ytm",
            status = "requires_resolution",
            createdAt = now,
            updatedAt = now
        )

        database.downloadJobDao().upsert(listOf(job))

        // 3. Vérification de la jointure
        val jobsWithTrack = database.downloadJobDao().getAllJobsWithTrackFlow().first()
        val matchingJob = jobsWithTrack.find { it.jobId == jobId }
        assertNotNull("Job should appear in getAllJobsWithTrackFlow", matchingJob)
        assertEquals("Bohemian Rhapsody", matchingJob?.title)
        assertEquals("Queen", matchingJob?.artistName)
        assertEquals("requires_resolution", matchingJob?.status)
    }
}
