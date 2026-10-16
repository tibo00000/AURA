package com.aura.music.data.player

import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.RepeatMode
import com.aura.music.domain.player.TrackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueManagerComprehensiveTest {

    private fun makeTrack(index: Int): QueuedTrack = QueuedTrack(
        internalId = "internal_$index",
        trackId = "trk_$index",
        title = "Title $index",
        artistName = "Artist",
        albumTitle = "Album",
        durationMs = 180_000L,
        contentUri = "file:///track_$index.mp3",
        coverUri = null,
        source = TrackSource.CONTEXT
    )

    @Test
    fun `next follows priority queue before context and stops at end when RepeatMode is Off`() {
        val manager = QueueManager()
        val tracks = (0..2).map { makeTrack(it) }
        manager.setContext("album", "alb_1", tracks, startIndex = 0)

        assertEquals("trk_0", manager.state.value.currentTrack?.trackId)

        // Add priority tracks via addToQueue (FIFO) and playNext (LIFO head insertion)
        manager.addToQueue(makeTrack(99))
        manager.playNext(makeTrack(88))

        // peekNext must return priority head without mutating state
        assertEquals("trk_88", manager.peekNext()?.trackId)
        assertEquals(2, manager.state.value.priorityQueue.size)

        // 1st next -> trk_88 (from playNext)
        assertEquals("trk_88", manager.next()?.trackId)
        assertEquals(TrackSource.PRIORITY, manager.state.value.currentTrack?.source)

        // 2nd next -> trk_99 (from addToQueue)
        assertEquals("trk_99", manager.next()?.trackId)
        assertTrue(manager.state.value.priorityQueue.isEmpty())

        // 3rd next -> resumes context at index 1 (trk_1)
        assertEquals("trk_1", manager.next()?.trackId)
        assertEquals("trk_2", manager.next()?.trackId)

        // End of context with RepeatMode.Off -> returns null and sets currentTrack to null
        assertNull(manager.next())
        assertNull(manager.state.value.currentTrack)
    }

    @Test
    fun `repeat modes cycle Off to All to One and control next behavior`() {
        val manager = QueueManager()
        val tracks = listOf(makeTrack(0), makeTrack(1))
        manager.setContext("playlist", "pl_1", tracks, startIndex = 1)

        // Off -> All
        manager.cycleRepeatMode()
        assertEquals(RepeatMode.All, manager.state.value.repeatMode)

        // At end of context (index 1), RepeatMode.All wraps around to index 0
        val wrapped = manager.next()
        assertNotNull(wrapped)
        assertEquals("trk_0", wrapped.trackId)

        // All -> One
        manager.cycleRepeatMode()
        assertEquals(RepeatMode.One, manager.state.value.repeatMode)

        // Even with a priority track queued, RepeatMode.One repeats the current track
        manager.playNext(makeTrack(50))
        val repeated = manager.next()
        assertEquals("trk_0", repeated?.trackId)
        assertEquals(1, manager.state.value.priorityQueue.size)

        // One -> Off
        manager.cycleRepeatMode()
        assertEquals(RepeatMode.Off, manager.state.value.repeatMode)
    }

    @Test
    fun `previous respects 3000ms restart threshold and pops history below threshold`() {
        val manager = QueueManager()
        val tracks = (0..2).map { makeTrack(it) }
        manager.setContext("album", "alb_1", tracks, startIndex = 1)

        // Starting at index 1 populates history with index 0
        assertEquals(1, manager.state.value.history.size)
        assertEquals("trk_1", manager.state.value.currentTrack?.trackId)

        // Position > 3000ms restarts current track without popping history
        val restarted = manager.previous(currentPositionMs = 3_500L)
        assertEquals("trk_1", restarted?.trackId)
        assertEquals(1, manager.state.value.history.size)

        // Position <= 3000ms pops history and restores context index 0
        val prev = manager.previous(currentPositionMs = 1_200L)
        assertEquals("trk_0", prev?.trackId)
        assertEquals(0, manager.state.value.context?.currentIndex)
        assertTrue(manager.state.value.history.isEmpty())

        // When history is empty, previous returns current track
        assertEquals("trk_0", manager.previous(currentPositionMs = 0L)?.trackId)
    }

    @Test
    fun `priority queue reordering removal and clearQueue preserve mode invariants`() {
        val manager = QueueManager()
        manager.restoreModes(shuffleEnabled = true, repeatMode = RepeatMode.All)
        assertTrue(manager.state.value.shuffleEnabled)
        assertEquals(RepeatMode.All, manager.state.value.repeatMode)

        val tracks = (0..4).map { makeTrack(it) }
        manager.setContext("album", "alb_1", tracks, startIndex = 2)

        // Engineering Rule #4: Shuffle keeps currentIndex (2) at position 0 of shuffledContextIndices
        val shuffled = manager.state.value.shuffledContextIndices
        assertNotNull(shuffled)
        assertEquals(2, shuffled.first())
        assertEquals(5, shuffled.toSet().size)

        manager.addToQueue(makeTrack(10))
        manager.addToQueue(makeTrack(11))
        manager.addToQueue(makeTrack(12))

        manager.reorderQueue(fromIndex = 2, toIndex = 0)
        assertEquals(listOf("trk_12", "trk_10", "trk_11"), manager.state.value.priorityQueue.map { it.trackId })

        manager.removeFromQueue(1)
        assertEquals(listOf("trk_12", "trk_11"), manager.state.value.priorityQueue.map { it.trackId })

        manager.clearQueue()
        assertNull(manager.state.value.context)
        assertNull(manager.state.value.currentTrack)
        assertTrue(manager.state.value.priorityQueue.isEmpty())
        assertTrue(manager.state.value.history.isEmpty())
        // Shuffle and repeat settings survive clearQueue
        assertTrue(manager.state.value.shuffleEnabled)
        assertEquals(RepeatMode.All, manager.state.value.repeatMode)
    }
}
