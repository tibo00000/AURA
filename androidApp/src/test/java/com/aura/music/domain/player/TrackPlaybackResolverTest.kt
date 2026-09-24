package com.aura.music.domain.player

import com.aura.music.domain.model.QueuedTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TrackPlaybackResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createValidMp3File(name: String): File {
        val file = tempFolder.newFile(name)
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x03, 0x00, 0x00)
        val content = ByteArray(4096)
        System.arraycopy(header, 0, content, 0, header.size)
        file.writeBytes(content)
        return file
    }

    private fun createQueuedTrack(
        id: String,
        contentUri: String? = null
    ): QueuedTrack {
        return QueuedTrack(
            trackId = id,
            title = "Track $id",
            artistName = "Artist",
            albumTitle = "Album",
            contentUri = contentUri,
            durationMs = 180_000L,
            coverUri = null,
            source = TrackSource.CONTEXT,
            internalId = "int_$id"
        )
    }

    @Test
    fun testResolveValidLocalFile() {
        val file = createValidMp3File("valid_song.mp3")
        val fileUri = file.toURI().toString()
        val purgedTracks = mutableListOf<String>()

        val resolver = TrackPlaybackResolver(
            downloadsDir = tempFolder.root,
            onPurgeTrack = { purgedTracks.add(it) },
            scope = testScope
        )

        val track = createQueuedTrack("track:deezer:101", contentUri = fileUri)
        val resolution = resolver.resolve(track)

        assertTrue(resolution is PlayableResolution.LocalFile)
        val localResolution = resolution as PlayableResolution.LocalFile
        assertEquals(file.absolutePath, localResolution.file.absolutePath)
        assertTrue(purgedTracks.isEmpty())
    }

    @Test
    fun testResolveCorruptLocalFileFallsBackToCloudAndPurges() {
        val corruptFile = tempFolder.newFile("corrupt_song.mp3")
        corruptFile.writeBytes(ByteArray(50)) // < 2048 bytes -> corrupt
        val fileUri = corruptFile.toURI().toString()
        val purgedTracks = mutableListOf<String>()

        val resolver = TrackPlaybackResolver(
            downloadsDir = tempFolder.root,
            onPurgeTrack = { purgedTracks.add(it) },
            scope = testScope
        )

        val track = createQueuedTrack("track:deezer:102", contentUri = fileUri)
        val resolution = resolver.resolve(track)

        // Must fallback to cloud stream
        assertTrue(resolution is PlayableResolution.CloudStream)
        val cloudResolution = resolution as PlayableResolution.CloudStream
        assertTrue(cloudResolution.streamUrl.contains("track:deezer:102"))

        // Corrupt file must be deleted from disk
        assertFalse(corruptFile.exists())

        // Run coroutine to verify DB purge callback
        testScope.advanceUntilIdle()
        assertEquals(listOf("track:deezer:102"), purgedTracks)
    }

    @Test
    fun testResolveDeadLinkFallsBackToCloudAndPurges() {
        val nonExistentPath = File(tempFolder.root, "ghost_song.mp3").toURI().toString()
        val purgedTracks = mutableListOf<String>()

        val resolver = TrackPlaybackResolver(
            downloadsDir = tempFolder.root,
            onPurgeTrack = { purgedTracks.add(it) },
            scope = testScope
        )

        val track = createQueuedTrack("track:deezer:103", contentUri = nonExistentPath)
        val resolution = resolver.resolve(track)

        // Must fallback to cloud stream
        assertTrue(resolution is PlayableResolution.CloudStream)

        // Run coroutine to verify DB purge callback
        testScope.advanceUntilIdle()
        assertEquals(listOf("track:deezer:103"), purgedTracks)
    }

    @Test
    fun testResolvePrivateDownloadsDirDetection() {
        val downloadsDir = tempFolder.newFolder("downloads")
        val trackId = "track:deezer:104"
        val expectedFileName = "${trackId.replace(':', ';')}.mp3"
        val validFile = File(downloadsDir, expectedFileName)
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x03, 0x00, 0x00)
        val content = ByteArray(4096)
        System.arraycopy(header, 0, content, 0, header.size)
        validFile.writeBytes(content)

        val resolver = TrackPlaybackResolver(
            downloadsDir = downloadsDir,
            onPurgeTrack = {},
            scope = testScope
        )

        // Track has no contentUri set in memory
        val track = createQueuedTrack(trackId, contentUri = null)
        val resolution = resolver.resolve(track)

        assertTrue(resolution is PlayableResolution.LocalFile)
        val localResolution = resolution as PlayableResolution.LocalFile
        assertEquals(validFile.absolutePath, localResolution.file.absolutePath)
    }

    @Test
    fun testResolveCloudStreamWhenNoLocalFiles() {
        val downloadsDir = tempFolder.newFolder("empty_downloads")
        val resolver = TrackPlaybackResolver(
            downloadsDir = downloadsDir,
            onPurgeTrack = {},
            scope = testScope
        )

        val track = createQueuedTrack("track:deezer:105", contentUri = null)
        val resolution = resolver.resolve(track)

        assertTrue(resolution is PlayableResolution.CloudStream)
        val cloud = resolution as PlayableResolution.CloudStream
        assertTrue(cloud.streamUrl.contains("/me/sync/files/track:deezer:105"))
    }

    @Test
    fun testResolveNotAvailableWhenTrackIdIsBlank() {
        val resolver = TrackPlaybackResolver(
            downloadsDir = tempFolder.root,
            onPurgeTrack = {},
            scope = testScope
        )

        val track = createQueuedTrack("", contentUri = null)
        val resolution = resolver.resolve(track)

        assertTrue(resolution is PlayableResolution.NotAvailable)
    }

    @Test
    fun testResolveScopedStorageContentUri() {
        val resolver = TrackPlaybackResolver(
            downloadsDir = tempFolder.root,
            onPurgeTrack = {},
            scope = testScope
        )

        val contentUri = "content://media/external/audio/media/999"
        val track = createQueuedTrack("local:audio:999", contentUri = contentUri)
        val resolution = resolver.resolve(track)

        assertTrue(resolution is PlayableResolution.LocalFile)
        val local = resolution as PlayableResolution.LocalFile
        assertEquals(contentUri, local.uri)
    }
}
