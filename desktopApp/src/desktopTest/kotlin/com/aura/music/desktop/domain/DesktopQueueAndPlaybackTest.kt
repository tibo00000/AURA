package com.aura.music.desktop.domain

import com.aura.music.data.local.AuraDatabase
import com.aura.music.data.player.QueueManager
import com.aura.music.desktop.DesktopPlaybackOrchestrator
import com.aura.music.domain.player.AudioPlayer
import com.aura.music.domain.player.PlaybackState
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.TrackSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DesktopQueueAndPlaybackTest {

    private lateinit var database: AuraDatabase
    private lateinit var tempDbFile: File
    private lateinit var tempAudioFile1: File
    private lateinit var tempAudioFile2: File

    @Before
    fun setUp() {
        tempDbFile = File.createTempFile("aura_queue_playback_test_", ".db")
        database = AuraDatabase.getInstance(tempDbFile.absolutePath)
        tempAudioFile1 = File.createTempFile("aura_test_track_1_", ".mp3").apply {
            writeBytes(ByteArray(128) { 1 })
        }
        tempAudioFile2 = File.createTempFile("aura_test_track_2_", ".mp3").apply {
            writeBytes(ByteArray(128) { 2 })
        }
    }

    @After
    fun tearDown() {
        try {
            if (::database.isInitialized) {
                database.close()
            }
        } catch (_: Throwable) {}
        tempDbFile.delete()
        File("${tempDbFile.absolutePath}-wal").delete()
        File("${tempDbFile.absolutePath}-shm").delete()
        tempAudioFile1.delete()
        tempAudioFile2.delete()
    }

    private fun createTrack(
        id: String,
        internalId: String = "int_$id",
        contentUri: String = "file:///music/$id.mp3"
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
            internalId = internalId
        )
    }

    private class FakeAudioPlayer : AudioPlayer {
        private var playingState: Boolean = false
        var loadedUri: String? = null
        var currentPosMs: Long = 0L
        var durationMs: Long = 180_000L
        var appliedVolume: Float = 1.0f
        var playCallCount: Int = 0
        var resumeCallCount: Int = 0

        override var onCompletionListener: (() -> Unit)? = null
        override var onErrorListener: ((String) -> Unit)? = null

        override fun play(uri: String) {
            playCallCount++
            loadedUri = uri
            currentPosMs = 0L
            playingState = true
        }

        override fun resume() {
            if (loadedUri != null) {
                resumeCallCount++
                playingState = true
            }
        }

        override fun hasLoadedMedia(): Boolean = loadedUri != null

        override fun pause() {
            playingState = false
        }

        override fun stop() {
            playingState = false
            currentPosMs = 0L
        }

        override fun seekTo(positionMs: Long) {
            currentPosMs = positionMs
        }

        override fun setVolume(volume: Float) {
            appliedVolume = volume.coerceIn(0f, 1f)
        }

        override fun getVolume(): Float = appliedVolume

        override fun getCurrentPosition(): Long = currentPosMs

        override fun getDuration(): Long = durationMs

        override fun isPlaying(): Boolean = playingState
    }

    @Test
    fun testJumpToUpcomingContextTrackPreservesShuffledOrder() {
        val qm = QueueManager()
        val tracks = (0..6).map { createTrack(it.toString(), "int_$it") }
        qm.setContext("playlist", "pl_1", tracks, startIndex = 0)
        qm.toggleShuffle()

        assertTrue(qm.state.value.shuffleEnabled)
        val upcomingBefore = qm.getUpcomingContextTracks()
        assertEquals(6, upcomingBefore.size)

        // L'utilisateur clique sur le 3ème morceau dans "Suite de la lecture"
        val clickedTrack = upcomingBefore[2]
        val expectedRemainingUpcoming = upcomingBefore.filter { it.internalId != clickedTrack.internalId }

        val jumped = qm.jumpToUpcomingContextTrack(clickedTrack.internalId)
        assertNotNull(jumped)
        assertEquals(clickedTrack.internalId, jumped?.internalId)
        assertEquals(clickedTrack.internalId, qm.state.value.currentTrack?.internalId)

        // Vérifie que le reste de la file en mode Shuffle n'a PAS été re-mélangé
        val upcomingAfter = qm.getUpcomingContextTracks()
        assertEquals(expectedRemainingUpcoming.map { it.internalId }, upcomingAfter.map { it.internalId })
        // Vérifie que la piste précédente a bien été ajoutée à l'historique
        assertEquals("int_0", qm.state.value.history.lastOrNull()?.internalId)
    }

    @Test
    fun testJumpToPriorityTrackPreservesContextAndRemainingPriority() {
        val qm = QueueManager()
        val tracks = (0..3).map { createTrack(it.toString(), "int_$it") }
        qm.setContext("playlist", "pl_1", tracks, startIndex = 0)
        qm.toggleShuffle()

        val upcomingContextBefore = qm.getUpcomingContextTracks().map { it.internalId }

        qm.addToQueue(createTrack("p1"))
        qm.addToQueue(createTrack("p2"))
        qm.addToQueue(createTrack("p3"))

        assertEquals(3, qm.state.value.priorityQueue.size)

        // Clic sur le 2ème élément de la file prioritaire (index 1 -> p2)
        val jumped = qm.jumpToPriorityTrack(1)
        assertNotNull(jumped)
        assertEquals("p2", jumped?.trackId)
        assertEquals("p2", qm.state.value.currentTrack?.trackId)

        // Il doit rester p1 et p3 dans la priority queue
        assertEquals(listOf("p1", "p3"), qm.state.value.priorityQueue.map { it.trackId })

        // Le contexte source mélangé doit être strictement intact
        assertEquals(upcomingContextBefore, qm.getUpcomingContextTracks().map { it.internalId })
    }

    @Test
    fun testPauseAndResumePreservesPositionWithoutRestartingTrack() {
        val fakePlayer = FakeAudioPlayer()
        val qm = QueueManager()
        val orchestrator = DesktopPlaybackOrchestrator(
            database = database,
            audioPlayer = fakePlayer,
            queueManager = qm,
            apiService = DesktopUpdateManagerTest.FakeAuraApiService()
        )

        val t1 = createTrack("t1", contentUri = tempAudioFile1.absolutePath)
        orchestrator.playTrack(
            trackId = t1.trackId,
            contextType = "library",
            contextId = "lib",
            contextTracks = listOf(t1),
            startIndex = 0
        )

        assertEquals(PlaybackState.Playing, orchestrator.uiState.value.playbackState)
        assertEquals(1, fakePlayer.playCallCount)

        // Simule l'avancement de la lecture à 45 secondes
        fakePlayer.currentPosMs = 45_000L

        // Pause
        orchestrator.togglePlayPause()
        assertEquals(PlaybackState.Paused, orchestrator.uiState.value.playbackState)
        assertEquals(45_000L, orchestrator.uiState.value.positionMs)

        // Play (reprise) : doit appeler resume() sans recréer le média ni remettre la position à 0
        orchestrator.togglePlayPause()
        assertEquals(PlaybackState.Playing, orchestrator.uiState.value.playbackState)
        assertEquals(1, fakePlayer.playCallCount)
        assertEquals(1, fakePlayer.resumeCallCount)
        assertEquals(45_000L, fakePlayer.currentPosMs)
        assertEquals(45_000L, orchestrator.uiState.value.positionMs)
    }

    @Test
    fun testVolumeAndMutePersistedAcrossTrackChanges() {
        val fakePlayer = FakeAudioPlayer()
        val qm = QueueManager()
        val orchestrator = DesktopPlaybackOrchestrator(
            database = database,
            audioPlayer = fakePlayer,
            queueManager = qm,
            apiService = DesktopUpdateManagerTest.FakeAuraApiService()
        )

        val t1 = createTrack("t1", contentUri = tempAudioFile1.absolutePath)
        val t2 = createTrack("t2", contentUri = tempAudioFile2.absolutePath)

        orchestrator.setVolume(0.35f)
        assertEquals(0.35f, orchestrator.volume.value, 0.001f)
        assertEquals(0.35f, fakePlayer.appliedVolume, 0.001f)

        // Lance un premier morceau puis passe au suivant : le volume ne doit pas être réinitialisé à 1.0f
        orchestrator.playTrack(
            trackId = t1.trackId,
            contextType = "library",
            contextId = "lib",
            contextTracks = listOf(t1, t2),
            startIndex = 0
        )
        assertEquals(0.35f, orchestrator.volume.value, 0.001f)
        assertEquals(0.35f, fakePlayer.appliedVolume, 0.001f)

        orchestrator.next()
        assertEquals(0.35f, orchestrator.volume.value, 0.001f)
        assertEquals(0.35f, fakePlayer.appliedVolume, 0.001f)

        // Mute / Unmute restaure bien le volume précédent (0.35f)
        orchestrator.toggleMute()
        assertTrue(orchestrator.isMuted.value)
        assertEquals(0f, fakePlayer.appliedVolume, 0.001f)

        orchestrator.toggleMute()
        assertEquals(false, orchestrator.isMuted.value)
        assertEquals(0.35f, orchestrator.volume.value, 0.001f)
        assertEquals(0.35f, fakePlayer.appliedVolume, 0.001f)
    }
}
