package com.aura.music.service

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.aura.music.data.local.PlaylistTrackRow
import com.aura.music.data.network.PlaylistAcquisitionStatus
import com.aura.music.data.player.PersistedPlaybackSnapshot
import com.aura.music.data.player.QueueManager
import com.aura.music.domain.player.RepeatMode
import com.aura.music.domain.player.buildPlaylistQueue
import com.aura.music.domain.player.matchesQueueTrack
import com.aura.music.domain.player.occurrenceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class PlaylistMedia3Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private val playlistId = "playlist:mix"
    private val content = "content://media/external/audio/media/42"

    private fun row(id: String, track: String = "recording", uri: String? = content) = PlaylistTrackRow(
        playlistItemId = id, playlistId = playlistId, trackId = track, position = 0, addedAt = 0,
        title = track, artistName = "Artist", albumTitle = null, contentUri = uri, durationMs = null,
        coverUri = null, artistId = null, albumId = null, isLiked = false,
    )

    @Test
    fun media3RepeatAllTransitionsBetweenOccurrencesOfTheSameRecording() {
        val queue = QueueManager()
        val eligible = buildPlaylistQueue(
            listOf(row("occ:first"), row("missing", "different", uri = null), row("occ:second")), emptySet(),
        )
        val resumed = restorePlaylistMediaItems(context, queue, playlistId, eligible, snapshot("occ:second"))
        val player = ExoPlayer.Builder(context).build()
        try {
            player.setMediaItems(resumed.mediaItems, resumed.startIndex, resumed.startPositionMs)
            assertEquals("occ:second", player.currentMediaItem?.occurrenceId())
            assertEquals(4200L, player.currentPosition)
            assertTrue(player.hasNextMediaItem())
            assertEquals("occ:second", queue.state.value.currentTrack?.internalId)
            val next = requireNotNull(queue.peekNext())
            player.seekToNextMediaItem()
            val item = requireNotNull(player.currentMediaItem)
            assertEquals("occ:first", item.occurrenceId())
            assertTrue(item.matchesQueueTrack(next))
            assertFalse(item.matchesQueueTrack(eligible.last()))
            assertEquals("occ:first", queue.next()?.internalId)
        } finally {
            player.release()
        }
    }

    @Test
    fun media3CopiesBrowseIdentityWithoutExposingTheAudioUri() {
        val eligible = buildPlaylistQueue(listOf(row("occ:first"), row("occ:second / été")), emptySet())
        val browse = eligible.map { it.toPlaylistMediaItem(context, playlistId) }
            .map { MediaItem.fromBundle(it.toBundleIncludeLocalConfiguration()) }
        assertEquals(2, browse.map { it.mediaId }.distinct().size)
        assertEquals(playlistId to "occ:second / été", decodePlaylistBrowseId(browse.last().mediaId))
        assertNull(browse.last().localConfiguration)
        assertTrue(browse.last().mediaMetadata.isPlayable == true)
        assertFalse(MediaItem.fromUri(Uri.parse(content)).buildUpon().setMediaId("recording").build()
            .matchesQueueTrack(eligible.last()))
    }

    @Test
    fun restorationUsesOnlyEligibleOccurrencesAndResetsOffsetAfterLoss() {
        val queue = QueueManager()
        val eligible = buildPlaylistQueue(listOf(row("occ:first"), row("occ:second", uri = null)), emptySet())
        val fallback = restorePlaylistMediaItems(context, queue, playlistId, eligible, snapshot("occ:second"))
        assertEquals(C.TIME_UNSET, fallback.startPositionMs)
        assertEquals(listOf("occ:first"), fallback.mediaItems.map { it.occurrenceId() })

        val empty = restorePlaylistMediaItems(context, QueueManager(), playlistId, emptyList(), snapshot("occ:second"))
        assertEquals(C.INDEX_UNSET, empty.startIndex)
        assertTrue(empty.mediaItems.isEmpty())
    }

    @Test
    fun browserRefreshesOnAvailabilityChangesAndAccountResetButIgnoresProgressAndEviction() {
        val pending = PlaylistAcquisitionStatus("track", "pending", "missing")
        val verifying = mapOf(playlistId to listOf(pending))
        val working = mapOf(playlistId to listOf(pending.copy(status = "admitted", jobStatus = "running")))
        val ready = mapOf(playlistId to listOf(pending.copy(status = "available", availability = "available")))

        assertEquals(emptyList<String>(), changedPlaylistBrowseFolders(verifying, working))
        assertEquals(listOf(playlistId), changedPlaylistBrowseFolders(working, ready))
        assertEquals(listOf(playlistId), changedPlaylistBrowseFolders(ready, emptyMap()))
        assertEquals(emptyList<String>(), changedPlaylistBrowseFolders(ready + ("evicted" to listOf(pending)), ready))
    }

    @Test
    fun browserPagesPreserveOrderWithLargeLibraryLimits() {
        val rows = (0 until 350).map { row("occ:$it") }
        val eligible = buildPlaylistQueue(rows, emptySet())
        assertEquals((100 until 200).map { "occ:$it" }, eligible.paginate(1, 100).map { it.internalId })
        assertEquals(eligible, eligible.paginate(0, Int.MAX_VALUE))
        assertTrue(eligible.paginate(Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
    }

    private fun snapshot(id: String) = PersistedPlaybackSnapshot(
        currentTrackId = "recording", contextType = "playlist", contextId = playlistId, contextIndex = 1,
        positionMs = 4200L, shuffleEnabled = false, repeatMode = RepeatMode.All, playlistOccurrenceId = id,
    )
}
