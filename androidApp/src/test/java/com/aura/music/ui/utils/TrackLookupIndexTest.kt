package com.aura.music.ui.utils

import com.aura.music.data.local.TrackListRow
import com.aura.music.data.network.SyncedFileResponseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLookupIndexTest {

    @Test
    fun testFastTimeFormatter() {
        assertEquals("0:00", FastTimeFormatter.formatDuration(0))
        assertEquals("3:45", FastTimeFormatter.formatDuration(225000))
        assertEquals("4:05", FastTimeFormatter.formatDuration(245000))
        assertEquals("10:00", FastTimeFormatter.formatDuration(600000))

        assertEquals("0.0 MB", FastTimeFormatter.formatFileSize(0))
        assertEquals("12.4 MB", FastTimeFormatter.formatFileSize(13002342))
    }

    @Test
    fun testNormalizedTrackKey() {
        val key1 = NormalizedTrackKey.from("  Hello World  ", " Adele ", " 25 ")
        assertEquals("hello world", key1.titleNorm)
        assertEquals("adele", key1.artistNorm)
        assertEquals("25", key1.albumNorm)

        val key2 = NormalizedTrackKey.from(null, null, null)
        assertEquals("", key2.titleNorm)
        assertEquals("", key2.artistNorm)
        assertEquals("", key2.albumNorm)
    }

    @Test
    fun testExactAndDeezerMatchPriority() {
        val downloadedTrack = TrackListRow(
            id = "local_1",
            artistId = "art_1",
            albumId = "alb_1",
            title = "Bohemian Rhapsody",
            artistName = "Queen",
            albumTitle = "A Night at the Opera",
            contentUri = "content://media/audio/1",
            durationMs = 354000L,
            coverUri = "content://cover/1",
            isLiked = true,
            createdAt = 1000L,
            updatedAt = 2000L
        )

        val nonDownloadedDuplicate = TrackListRow(
            id = "local_2",
            artistId = "art_1",
            albumId = "alb_1",
            title = "Bohemian Rhapsody",
            artistName = "Queen",
            albumTitle = "A Night at the Opera",
            contentUri = null,
            durationMs = 354000L,
            coverUri = null,
            isLiked = false,
            createdAt = 500L,
            updatedAt = 500L
        )

        val index = TrackLookupIndex.build(
            allLibraryTracks = listOf(nonDownloadedDuplicate, downloadedTrack),
            cloudFiles = emptyList(),
            syncedCloudTrackIds = emptySet()
        )

        val match = index.findLocalMatch(
            trackId = "any_online_id",
            title = "bohemian rhapsody",
            artistName = "queen",
            albumTitle = "a night at the opera"
        )

        assertNotNull(match)
        assertEquals("local_1", match?.id)
        assertEquals("content://media/audio/1", match?.contentUri)
    }

    @Test
    fun testCloudSyncMatching() {
        val cloudFile = SyncedFileResponseData(
            id = "cloud_1",
            trackId = "cloud_track_123",
            title = "Starboy",
            artistName = "The Weeknd",
            albumTitle = "Starboy",
            sizeBytes = 8500000L,
            updatedAt = "2026-08-23T00:00:00Z"
        )

        val index = TrackLookupIndex.build(
            allLibraryTracks = emptyList(),
            cloudFiles = listOf(cloudFile),
            syncedCloudTrackIds = setOf("cloud_track_123")
        )

        assertTrue(index.isCloudSynced("cloud_track_123", "Starboy", "The Weeknd", "Starboy"))
        assertTrue(index.isCloudSynced("other_id", "starboy ", "the weeknd", "starboy"))
        // Doublet match when album is missing
        assertTrue(index.isCloudSynced("other_id_2", "starboy", "the weeknd", null))
        assertFalse(index.isCloudSynced("unknown_id", "Blinding Lights", "The Weeknd", "After Hours"))
    }
}
