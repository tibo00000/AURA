package com.aura.music.data.player

import com.aura.music.domain.player.PlaybackContext
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.RepeatMode
import com.aura.music.domain.player.TrackSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Etat observable de la queue gere par QueueManager.
 */
data class QueueState(
    val context: PlaybackContext? = null,
    val currentTrack: QueuedTrack? = null,
    val priorityQueue: List<QueuedTrack> = emptyList(),
    val history: List<QueuedTrack> = emptyList(),
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val shuffledContextIndices: List<Int>? = null,
)

/**
 * Gere playback context, priority queue et history.
 *
 * Gouverne par :
 * - docs/android/player/queue-rules.md
 * - docs/domain/playback-model.md
 * - docs/domain/playback-user-flows.md
 *
 * La priority queue reste en memoire et n'est pas persistee (cf. room-schema.md).
 */
class QueueManager {

    companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
    }

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    /**
     * Definit un nouveau contexte de lecture.
     * Reinitialise la priority queue et l'historique.
     */
    fun setContext(
        type: String,
        id: String,
        tracks: List<QueuedTrack>,
        startIndex: Int,
    ) {
        val contextTracks = tracks.map { it.copy(source = TrackSource.CONTEXT) }
        val context = PlaybackContext(
            type = type,
            id = id,
            tracks = contextTracks,
            currentIndex = startIndex.coerceIn(0, (contextTracks.size - 1).coerceAtLeast(0)),
        )
        _state.update {
            QueueState(
                context = context,
                currentTrack = context.currentTrack,
                priorityQueue = emptyList(),
                history = emptyList(),
                shuffleEnabled = it.shuffleEnabled,
                repeatMode = it.repeatMode,
                shuffledContextIndices = if (it.shuffleEnabled) {
                    buildShuffledIndices(contextTracks.size, startIndex)
                } else {
                    null
                },
            )
        }
    }

    /**
     * Resout la prochaine piste.
     * Priorite : priority queue > contexte source > repeat > idle.
     * Retourne le QueuedTrack suivant ou null si fin de lecture.
     */
    fun next(): QueuedTrack? {
        var result: QueuedTrack? = null
        _state.update { current ->
            val currentTrack = current.currentTrack
            val updatedHistory = if (currentTrack != null) {
                current.history + currentTrack
            } else {
                current.history
            }

            if (current.repeatMode == RepeatMode.One && currentTrack != null) {
                result = currentTrack
                return@update current.copy(history = updatedHistory)
            }

            if (current.priorityQueue.isNotEmpty()) {
                val nextTrack = current.priorityQueue.first()
                result = nextTrack
                return@update current.copy(
                    currentTrack = nextTrack,
                    priorityQueue = current.priorityQueue.drop(1),
                    history = updatedHistory,
                )
            }

            val context = current.context ?: run {
                result = null
                return@update current.copy(
                    currentTrack = null,
                    history = updatedHistory,
                )
            }

            val nextIndex = resolveNextContextIndex(current)
            if (nextIndex != null) {
                val updatedContext = context.copy(currentIndex = nextIndex)
                val nextTrack = updatedContext.currentTrack
                result = nextTrack
                return@update current.copy(
                    context = updatedContext,
                    currentTrack = nextTrack,
                    history = updatedHistory,
                )
            }

            if (current.repeatMode == RepeatMode.All) {
                val restartIndex = if (current.shuffledContextIndices != null) {
                    current.shuffledContextIndices.firstOrNull() ?: 0
                } else {
                    0
                }
                val updatedContext = context.copy(currentIndex = restartIndex)
                val nextTrack = updatedContext.currentTrack
                result = nextTrack
                return@update current.copy(
                    context = updatedContext,
                    currentTrack = nextTrack,
                    history = updatedHistory,
                    shuffledContextIndices = if (current.shuffleEnabled) {
                        buildShuffledIndices(context.tracks.size, restartIndex)
                    } else {
                        null
                    },
                )
            }

            result = null
            current.copy(
                currentTrack = null,
                history = updatedHistory,
            )
        }
        return result
    }

    /**
     * Resout la piste precedente.
     * Si positionMs > seuil, redemarrage (retourne le track courant).
     * Sinon, retour a l'historique reel.
     */
    fun previous(currentPositionMs: Long): QueuedTrack? {
        var result: QueuedTrack? = null
        _state.update { current ->
            if (currentPositionMs > RESTART_THRESHOLD_MS && current.currentTrack != null) {
                result = current.currentTrack
                return@update current
            }

            if (current.history.isNotEmpty()) {
                val previousTrack = current.history.last()
                result = previousTrack

                val restoredContext = if (previousTrack.source == TrackSource.CONTEXT) {
                    current.context?.let { ctx ->
                        val idx = ctx.tracks.indexOfFirst { it.trackId == previousTrack.trackId }
                        if (idx >= 0) ctx.copy(currentIndex = idx) else ctx
                    }
                } else {
                    current.context
                }

                return@update current.copy(
                    context = restoredContext ?: current.context,
                    currentTrack = previousTrack,
                    history = current.history.dropLast(1),
                )
            }

            result = current.currentTrack
            current
        }
        return result
    }

    /**
     * Ajoute une piste a la priority queue.
     * Les doublons sont autorises si voulus par l'utilisateur (cf. queue-rules.md).
     */
    fun addToQueue(track: QueuedTrack) {
        _state.update { current ->
            current.copy(
                priorityQueue = current.priorityQueue + track.copy(source = TrackSource.PRIORITY),
            )
        }
    }

    /**
     * Retire une piste de la priority queue par index.
     * La piste courante ne peut pas etre retiree (cf. queue-rules.md).
     */
    fun removeFromQueue(index: Int) {
        _state.update { current ->
            if (index < 0 || index >= current.priorityQueue.size) return@update current
            current.copy(
                priorityQueue = current.priorityQueue.toMutableList().apply { removeAt(index) },
            )
        }
    }

    /**
     * Reordonne la priority queue.
     * N'affecte pas le contexte source (cf. queue-rules.md).
     */
    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        _state.update { current ->
            val queue = current.priorityQueue.toMutableList()
            if (fromIndex < 0 || fromIndex >= queue.size || toIndex < 0 || toIndex >= queue.size) {
                return@update current
            }
            val item = queue.removeAt(fromIndex)
            queue.add(toIndex, item)
            current.copy(priorityQueue = queue)
        }
    }

    /**
     * Bascule le mode shuffle.
     * Shuffle reordonne le contexte source, pas la priority queue (cf. playback-model.md).
     */
    fun toggleShuffle() {
        _state.update { current ->
            val newShuffle = !current.shuffleEnabled
            current.copy(
                shuffleEnabled = newShuffle,
                shuffledContextIndices = if (newShuffle && current.context != null) {
                    buildShuffledIndices(
                        current.context.tracks.size,
                        current.context.currentIndex,
                    )
                } else {
                    null
                },
            )
        }
    }

    /**
     * Cycle le mode repeat : Off -> All -> One -> Off.
     */
    fun cycleRepeatMode() {
        _state.update { current ->
            val next = when (current.repeatMode) {
                RepeatMode.Off -> RepeatMode.All
                RepeatMode.All -> RepeatMode.One
                RepeatMode.One -> RepeatMode.Off
            }
            current.copy(repeatMode = next)
        }
    }

    /**
     * Restaure les modes depuis un snapshot persiste.
     */
    fun restoreModes(shuffleEnabled: Boolean, repeatMode: RepeatMode) {
        _state.update { it.copy(shuffleEnabled = shuffleEnabled, repeatMode = repeatMode) }
    }

    private fun resolveNextContextIndex(state: QueueState): Int? {
        val context = state.context ?: return null
        val shuffled = state.shuffledContextIndices
        return if (shuffled != null) {
            val currentShufflePos = shuffled.indexOf(context.currentIndex)
            val nextShufflePos = currentShufflePos + 1
            if (nextShufflePos < shuffled.size) shuffled[nextShufflePos] else null
        } else {
            val nextIndex = context.currentIndex + 1
            if (nextIndex < context.tracks.size) nextIndex else null
        }
    }

    private fun buildShuffledIndices(size: Int, currentIndex: Int): List<Int> {
        if (size <= 1) return listOf(currentIndex).take(size)
        val remaining = (0 until size).filter { it != currentIndex }.shuffled()
        return listOf(currentIndex) + remaining
    }
}
