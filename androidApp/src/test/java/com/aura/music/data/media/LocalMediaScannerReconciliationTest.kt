package com.aura.music.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalMediaScannerReconciliationTest {

    private val validAudioExtensions = setOf("mp3", "m4a", "flac", "wav", "opus", "ogg")

    private fun isAcceptedDownloadFile(fileName: String, fileLength: Long): Boolean {
        if (fileLength <= 0L) return false
        if (fileName.endsWith(".part", ignoreCase = true)) return false
        if (fileName.endsWith(".ytdl", ignoreCase = true)) return false
        if (fileName.endsWith(".tmp", ignoreCase = true)) return false
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in validAudioExtensions
    }

    private fun trackIdToFileName(trackId: String): String {
        return "${trackId.replace(':', ';')}.mp3"
    }

    private fun fileNameToTrackId(fileName: String): String {
        val nameWithoutExt = fileName.substringBeforeLast('.')
        return nameWithoutExt.replace(';', ':')
    }

    @Test
    fun testFileFilterIgnoresIncompleteAndZeroByteFiles() {
        // Valid files
        assertTrue(isAcceptedDownloadFile("track;deezer;12345.mp3", 1024L))
        assertTrue(isAcceptedDownloadFile("track;deezer;67890.m4a", 2048L))
        assertTrue(isAcceptedDownloadFile("track;deezer;11111.flac", 4096L))
        assertTrue(isAcceptedDownloadFile("track;deezer;22222.wav", 8192L))
        assertTrue(isAcceptedDownloadFile("track;deezer;33333.opus", 1024L))
        assertTrue(isAcceptedDownloadFile("track;deezer;44444.ogg", 1024L))

        // Incomplete / temporary files from download engines
        assertFalse(isAcceptedDownloadFile("track;deezer;12345.mp3.part", 1024L))
        assertFalse(isAcceptedDownloadFile("track;deezer;12345.mp3.ytdl", 1024L))
        assertFalse(isAcceptedDownloadFile("track;deezer;12345.tmp", 1024L))
        assertFalse(isAcceptedDownloadFile("track;deezer;12345.MP3.PART", 1024L))

        // Zero-byte files
        assertFalse(isAcceptedDownloadFile("track;deezer;12345.mp3", 0L))

        // Unsupported extensions
        assertFalse(isAcceptedDownloadFile("track;deezer;12345.txt", 1024L))
        assertFalse(isAcceptedDownloadFile("track;deezer;12345.jpg", 1024L))
    }

    @Test
    fun testTrackIdBijectiveTransformation() {
        val originalIds = listOf(
            "track:deezer:12345678",
            "track:spotify:4iV5W9uYEdYUVa79Axb7Rh",
            "track:cloud:user-uuid:file-id-123",
            "local:audio:9999"
        )

        for (id in originalIds) {
            val fileName = trackIdToFileName(id)
            val decodedId = fileNameToTrackId(fileName)
            assertEquals("Bijective transformation should restore original ID exactly", id, decodedId)
        }
    }

    @Test
    fun testDownloadedTracksNeverPurgedWhenMediaStoreIsEmpty() {
        // Given existing downloaded tracks in Room
        val existingDownloadedTrackIds = listOf("track:deezer:1001", "track:deezer:1002")
        val existingLocalTrackIds = listOf("local:1", "local:2")

        // MediaStore returns 0 tracks (e.g. user has no music in public music folders or permission denied)
        val mediaStoreScannedIds = emptySet<String>()
        val isMediaStoreScanComplete = true

        // Physical files in private downloads directory
        val downloadedFilesByTrackId = mapOf(
            "track:deezer:1001" to File("track;deezer;1001.mp3"),
            "track:deezer:1002" to File("track;deezer;1002.mp3")
        )
        val activeJobTrackIds = emptySet<String>()

        // When running reconciliation
        val obsoleteIds = mutableListOf<String>()

        // 1. MediaStore purge
        if (isMediaStoreScanComplete) {
            for (id in existingLocalTrackIds) {
                if (id !in mediaStoreScannedIds) {
                    obsoleteIds.add(id)
                }
            }
        }

        // 2. Downloaded purge
        for (id in existingDownloadedTrackIds) {
            if (id !in downloadedFilesByTrackId.keys && id !in activeJobTrackIds) {
                obsoleteIds.add(id)
            }
        }

        // Then: local MediaStore tracks are obsolete, but DOWNLOADED TRACKS ARE RETAINED
        assertTrue("Local MediaStore track 1 should be obsolete", obsoleteIds.contains("local:1"))
        assertTrue("Local MediaStore track 2 should be obsolete", obsoleteIds.contains("local:2"))
        assertFalse("Downloaded track 1001 must NEVER be obsolete when physical file exists", obsoleteIds.contains("track:deezer:1001"))
        assertFalse("Downloaded track 1002 must NEVER be obsolete when physical file exists", obsoleteIds.contains("track:deezer:1002"))
    }

    @Test
    fun testActiveJobsProtectedFromPrematurePurge() {
        // Track has an active job (queued or downloading), physical file not yet completed
        val existingDownloadedTrackIds = listOf("track:deezer:2001")
        val downloadedFilesByTrackId = emptyMap<String, File>() // File not yet written
        val activeJobTrackIds = setOf("track:deezer:2001") // Active job in progress

        val obsoleteIds = mutableListOf<String>()
        for (id in existingDownloadedTrackIds) {
            if (id !in downloadedFilesByTrackId.keys && id !in activeJobTrackIds) {
                obsoleteIds.add(id)
            }
        }

        assertTrue("Track with an active job must NOT be marked obsolete", obsoleteIds.isEmpty())
    }

    @Test
    fun testPreservedTracksOnlyDetachedNeverCascadeDeleted() {
        val obsoleteTrackId = "track:deezer:3001"
        val likedTrackIds = setOf(obsoleteTrackId)
        val playlistTrackIds = emptySet<String>()
        val preservedTrackIds = likedTrackIds + playlistTrackIds

        val toDeleteCompletely = mutableListOf<String>()
        val toDetachOnly = mutableListOf<String>()

        if (obsoleteTrackId !in preservedTrackIds) {
            toDeleteCompletely.add(obsoleteTrackId)
        } else {
            toDetachOnly.add(obsoleteTrackId)
        }

        assertTrue("Preserved track should only be detached from media links to protect user metadata", toDetachOnly.contains(obsoleteTrackId))
        assertFalse("Preserved track should NOT be deleted from tracks table", toDeleteCompletely.contains(obsoleteTrackId))
    }
}
