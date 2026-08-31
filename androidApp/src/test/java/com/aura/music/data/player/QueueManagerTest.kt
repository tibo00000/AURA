package com.aura.music.data.player

import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueManagerTest {

    private fun createTrack(id: String, internalId: String = "int_$id"): QueuedTrack {
        return QueuedTrack(
            trackId = id,
            title = "Track $id",
            artistName = "Artist",
            albumTitle = "Album",
            contentUri = "content://media/$id",
            durationMs = 200_000L,
            coverUri = null,
            source = TrackSource.CONTEXT,
            internalId = internalId
        )
    }

    @Test
    fun testAdjacentStepReorder() {
        val qm = QueueManager()
        val tracks = listOf(
            createTrack("0", "int_0"), // Current active track
            createTrack("1", "int_1"), // Upcoming 1
            createTrack("2", "int_2"), // Upcoming 2
            createTrack("3", "int_3")  // Upcoming 3
        )
        qm.setContext("playlist", "pl_1", tracks, startIndex = 0)

        // Échange adjacent entre int_1 et int_2 (équivalent à onMove dans Compose reorderable)
        qm.reorderUpcomingContextTrack("int_1", "int_2")

        val upcoming = qm.getUpcomingContextTracks()
        assertEquals(3, upcoming.size)
        assertEquals("int_2", upcoming[0].internalId)
        assertEquals("int_1", upcoming[1].internalId)
        assertEquals("int_3", upcoming[2].internalId)
    }

    @Test
    fun testCumulativeMultiStepReorder() {
        val qm = QueueManager()
        val tracks = listOf(
            createTrack("0", "int_0"), // Active track
            createTrack("1", "int_1"), // Upcoming 0
            createTrack("2", "int_2"), // Upcoming 1
            createTrack("3", "int_3"), // Upcoming 2
            createTrack("4", "int_4")  // Upcoming 3
        )
        qm.setContext("playlist", "pl_1", tracks, startIndex = 0)

        // Déplacement simulé de int_1 sur 3 crans vers le bas via étapes successives
        // Étape 1 : échange avec int_2 -> [int_2, int_1, int_3, int_4]
        qm.reorderUpcomingContextTrack("int_1", "int_2")
        // Étape 2 : échange avec int_3 -> [int_2, int_3, int_1, int_4]
        qm.reorderUpcomingContextTrack("int_1", "int_3")
        // Étape 3 : échange avec int_4 -> [int_2, int_3, int_4, int_1]
        qm.reorderUpcomingContextTrack("int_1", "int_4")

        val upcoming = qm.getUpcomingContextTracks()
        assertEquals(4, upcoming.size)
        assertEquals("int_2", upcoming[0].internalId)
        assertEquals("int_3", upcoming[1].internalId)
        assertEquals("int_4", upcoming[2].internalId)
        assertEquals("int_1", upcoming[3].internalId)
    }

    @Test
    fun testCurrentTrackInvariance() {
        val qm = QueueManager()
        val tracks = listOf(
            createTrack("0", "int_0"),
            createTrack("1", "int_1"), // Active track
            createTrack("2", "int_2"),
            createTrack("3", "int_3")
        )
        qm.setContext("playlist", "pl_1", tracks, startIndex = 1)

        val activeBefore = qm.state.value.currentTrack
        assertEquals("int_1", activeBefore?.internalId)
        assertEquals(1, qm.state.value.context?.currentIndex)

        // Réordonnancement dans la section à venir
        qm.reorderUpcomingContextTrack("int_2", "int_3")

        val activeAfter = qm.state.value.currentTrack
        assertEquals("int_1", activeAfter?.internalId)
        assertEquals(1, qm.state.value.context?.currentIndex)
    }

    @Test
    fun testRemoveUpcomingTrack() {
        val qm = QueueManager()
        val tracks = listOf(
            createTrack("0", "int_0"), // Active track
            createTrack("1", "int_1"),
            createTrack("2", "int_2"),
            createTrack("3", "int_3")
        )
        qm.setContext("playlist", "pl_1", tracks, startIndex = 0)

        // Suppression d'un morceau à venir
        qm.removeUpcomingContextTrack("int_2")

        val upcoming = qm.getUpcomingContextTracks()
        assertEquals(2, upcoming.size)
        assertEquals("int_1", upcoming[0].internalId)
        assertEquals("int_3", upcoming[1].internalId)

        // Tentative de suppression du morceau en cours de lecture : doit être ignorée
        qm.removeUpcomingContextTrack("int_0")
        assertEquals(0, qm.state.value.context?.currentIndex)
        assertEquals("int_0", qm.state.value.currentTrack?.internalId)
    }

    @Test
    fun testShuffleReorderAndRemove() {
        val qm = QueueManager()
        val tracks = (0..5).map { createTrack(it.toString(), "int_$it") }
        qm.setContext("playlist", "pl_1", tracks, startIndex = 0)
        qm.toggleShuffle()

        assertTrue(qm.state.value.shuffleEnabled)
        val shuffledIndices = qm.state.value.shuffledContextIndices
        assertNotNull(shuffledIndices)
        assertEquals(6, shuffledIndices?.size)

        val upcoming = qm.getUpcomingContextTracks()
        assertEquals(5, upcoming.size)

        // Réordonnancement des 2 premières pistes à venir
        val firstUpcoming = upcoming[0].internalId
        val secondUpcoming = upcoming[1].internalId
        qm.reorderUpcomingContextTrack(firstUpcoming, secondUpcoming)

        val upcomingAfter = qm.getUpcomingContextTracks()
        assertEquals(secondUpcoming, upcomingAfter[0].internalId)
        assertEquals(firstUpcoming, upcomingAfter[1].internalId)

        // Suppression d'une piste à venir en mode Shuffle
        val targetToRemove = upcomingAfter[2].internalId
        qm.removeUpcomingContextTrack(targetToRemove)
        val upcomingAfterRemove = qm.getUpcomingContextTracks()
        assertEquals(4, upcomingAfterRemove.size)
        assertTrue(upcomingAfterRemove.none { it.internalId == targetToRemove })
    }

    @Test
    fun testBoundaryGuards() {
        val qm = QueueManager()
        // Sur une queue vide, aucun crash
        qm.reorderUpcomingContextTrack("unknown_1", "unknown_2")
        qm.removeUpcomingContextTrack("unknown_1")
        assertNull(qm.state.value.context)

        // Sur 1 seul élément
        qm.setContext("single", "1", listOf(createTrack("1", "int_1")), startIndex = 0)
        qm.reorderUpcomingContextTrack("int_1", "int_1")
        qm.removeUpcomingContextTrack("int_1")
        assertEquals("int_1", qm.state.value.currentTrack?.internalId)
    }
}
