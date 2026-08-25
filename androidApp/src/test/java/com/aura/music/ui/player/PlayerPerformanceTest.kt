package com.aura.music.ui.player

import com.aura.music.ui.utils.FastTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPerformanceTest {

    private fun legacyFormatDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    @Test
    fun testFastTimeFormatterGoldenComparisonUnderOneHour() {
        // Test golden d'équivalence stricte caractère pour caractère sur un large échantillon < 1h
        val testValues = longArrayOf(
            0L, -100L, -5000L,
            1000L, 5000L, 9000L, 10000L, 15000L, 59000L,
            60000L, 65000L, 119000L, 120000L, 125000L,
            245000L, 354000L, 599000L, 600000L, 1800000L,
            3540000L, 3599000L, 3599999L
        )

        for (ms in testValues) {
            val expected = legacyFormatDuration(ms)
            val actual = FastTimeFormatter.formatDuration(ms)
            assertEquals("Divergence pour $ms ms", expected, actual)
        }
    }

    @Test
    fun testFastTimeFormatterHoursSupport() {
        // Durée >= 1h : vérification du format h:mm:ss
        assertEquals("1:00:00", FastTimeFormatter.formatDuration(3600000L))
        assertEquals("1:02:05", FastTimeFormatter.formatDuration(3725000L))
        assertEquals("1:15:30", FastTimeFormatter.formatDuration(4530000L))
        assertEquals("2:05:09", FastTimeFormatter.formatDuration(7509000L))
        assertEquals("10:00:00", FastTimeFormatter.formatDuration(36000000L))
    }

    @Test
    fun testSliderFractionAndBoundaryMath() {
        val rangeStart = 0f
        val rangeEnd = 180000f // 3 minutes en ms
        val rangeLength = rangeEnd - rangeStart

        // 0%
        val frac0 = ((0f - rangeStart) / rangeLength).coerceIn(0f, 1f)
        assertEquals(0f, frac0, 0.0001f)

        // 50%
        val frac50 = ((90000f - rangeStart) / rangeLength).coerceIn(0f, 1f)
        assertEquals(0.5f, frac50, 0.0001f)

        // 100%
        val frac100 = ((180000f - rangeStart) / rangeLength).coerceIn(0f, 1f)
        assertEquals(1f, frac100, 0.0001f)

        // Valeur négative / dépassement
        val fracUnder = ((-500f - rangeStart) / rangeLength).coerceIn(0f, 1f)
        assertEquals(0f, fracUnder, 0.0001f)

        val fracOver = ((250000f - rangeStart) / rangeLength).coerceIn(0f, 1f)
        assertEquals(1f, fracOver, 0.0001f)

        // Test mathématique RTL (Inversion)
        val ltrFrac = 0.25f
        val rtlFrac = 1f - ltrFrac
        assertEquals(0.75f, rtlFrac, 0.0001f)
    }

    @Test
    fun testQueueManagerClearPriorityQueueLeavesCurrentTrackIntact() {
        val qm = com.aura.music.data.player.QueueManager()
        val currentTrack = com.aura.music.domain.player.QueuedTrack(
            trackId = "track_playing",
            title = "Playing Title",
            artistName = "Playing Artist",
            durationMs = 200000L
        )
        val priorityTrack1 = com.aura.music.domain.player.QueuedTrack(
            trackId = "track_p1",
            title = "P1 Title",
            artistName = "P1 Artist",
            durationMs = 180000L
        )
        val priorityTrack2 = com.aura.music.domain.player.QueuedTrack(
            trackId = "track_p2",
            title = "P2 Title",
            artistName = "P2 Artist",
            durationMs = 190000L
        )

        // Set context / current track
        qm.setContext(
            type = "album",
            id = "album_1",
            tracks = listOf(currentTrack),
            startIndex = 0
        )
        qm.addToQueue(priorityTrack1)
        qm.addToQueue(priorityTrack2)

        assertEquals(2, qm.state.value.priorityQueue.size)
        assertEquals("track_playing", qm.state.value.currentTrack?.trackId)

        // Clear priority queue
        qm.clearPriorityQueue()

        assertEquals(0, qm.state.value.priorityQueue.size)
        assertEquals("track_playing", qm.state.value.currentTrack?.trackId)
    }
}
