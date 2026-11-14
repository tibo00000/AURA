package com.aura.music.domain.player

import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.player.QueueManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPlaybackTest {
    private fun row(occurrence: String, track: String, uri: String? = null) = PlaylistTrackRow(
        playlistItemId = occurrence, playlistId = "mix", trackId = track, position = 0, addedAt = 0,
        title = track, artistName = "Artist", albumTitle = null, contentUri = uri, durationMs = null,
        coverUri = null, artistId = null, albumId = null, isLiked = false,
    )

    @Test
    fun mixedQueueStartsFromKnownSourcesAndRetainsTwoOccurrences() {
        val rows = listOf(
            row("local-first", "same", "content://media/42"),
            row("missing", "missing"),
            row("local-second", "same", "content://media/42"),
            row("cloud-only", "stream"),
            row("unknown", "unknown", "https://example.com/unconfirmed"),
        )
        val eligible = buildPlaylistQueue(rows, setOf("stream"))
        assertEquals(listOf("local-first", "local-second", "cloud-only"), eligible.map { it.internalId })
        assertFalse(requireNotNull(eligible.first().playlistCloudAvailable))
        assertTrue(requireNotNull(eligible.last().playlistCloudAvailable))
        assertEquals(1, findPlaylistResumeIndex(eligible, "same", "local-second"))
        assertEquals(-1, findPlaylistResumeIndex(eligible, "same", null))
        assertEquals(-1, findPlaylistResumeIndex(eligible, "missing", "missing"))
        val manager = QueueManager()
        manager.setContext("playlist", "mix", eligible, 0)
        assertEquals("local-second", manager.next()?.internalId)
        assertEquals("cloud-only", manager.next()?.internalId)
        assertEquals("local-second", manager.previous(0)?.internalId)
        assertEquals(1, manager.state.value.context?.currentIndex)
        manager.toggleShuffle()
        assertEquals("local-second", manager.state.value.currentTrack?.internalId)
    }

    @Test
    fun availabilityUpdatesCannotChangeTheQueueAlreadyInUse() {
        val rows = listOf(row("local", "same", "content://media/42"), row("future", "stream"))
        val manager = QueueManager()
        manager.setContext("playlist", "mix", buildPlaylistQueue(rows, emptySet()), 0)
        assertEquals(2, buildPlaylistQueue(rows, setOf("stream")).size)
        assertEquals(emptyList<QueuedTrack>(), manager.getUpcomingContextTracks())
        assertEquals("local", manager.state.value.currentTrack?.internalId)
    }

    @Test
    fun accountChangeDropsCloudEntriesAndKeepsTheActiveLocalOccurrenceAndShuffleOrder() {
        val rows = listOf(
            row("cloud-first", "one"),
            row("local", "two", "content://media/42"),
            row("cloud-next", "three"),
            row("local-repeat", "two", "content://media/42"),
            row("other-local", "four", "content://media/43"),
        )
        val tracks = buildPlaylistQueue(rows, setOf("one", "two", "three"))
        val manager = QueueManager()
        manager.setContext("playlist", "mix", tracks, 1)
        manager.toggleShuffle()
        manager.addToQueue(tracks[2])
        val localUpcoming = manager.getUpcomingContextTracks().filter { it.contentUri != null }.map { it.internalId }

        manager.invalidatePlaylistCloudSources()

        val state = manager.state.value
        assertEquals("local", state.currentTrack?.internalId)
        assertEquals(false, state.currentTrack?.playlistCloudAvailable)
        assertTrue(state.priorityQueue.isEmpty())
        assertTrue(state.history.isEmpty())
        assertEquals(localUpcoming, manager.getUpcomingContextTracks().map { it.internalId })
    }
}
