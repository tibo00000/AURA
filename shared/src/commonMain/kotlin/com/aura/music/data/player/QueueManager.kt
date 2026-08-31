package com.aura.music.data.player

import com.aura.music.domain.player.PlaybackContext
import com.aura.music.domain.player.QueuedTrack
import com.aura.music.domain.player.RepeatMode
import com.aura.music.domain.player.TrackSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

import com.aura.music.domain.player.generateUuid

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
        val safeIndex = startIndex.coerceIn(0, (contextTracks.size - 1).coerceAtLeast(0))
        val context = PlaybackContext(
            type = type,
            id = id,
            tracks = contextTracks,
            currentIndex = safeIndex,
        )
        val historyTracks = if (safeIndex > 0) {
            contextTracks.take(safeIndex)
        } else {
            emptyList()
        }
        _state.update {
            QueueState(
                context = context,
                currentTrack = context.currentTrack,
                priorityQueue = emptyList(),
                history = historyTracks,
                shuffleEnabled = it.shuffleEnabled,
                repeatMode = it.repeatMode,
                shuffledContextIndices = if (it.shuffleEnabled) {
                    buildShuffledIndices(contextTracks.size, safeIndex)
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
                priorityQueue = current.priorityQueue + track.copy(
                    source = TrackSource.PRIORITY,
                    internalId = generateUuid()
                ),
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
     * Vide intégralement la priority queue en une mutation atomique.
     * La piste courante n'est pas affectée.
     */
    fun clearPriorityQueue() {
        _state.update { current ->
            if (current.priorityQueue.isEmpty()) return@update current
            current.copy(priorityQueue = emptyList())
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

    fun restoreModes(shuffleEnabled: Boolean, repeatMode: RepeatMode) {
        _state.update { it.copy(shuffleEnabled = shuffleEnabled, repeatMode = repeatMode) }
    }

    /**
     * Reinitialise completement l'etat de la file d'attente (vide le contexte, la queue de priorite, etc.).
     */
    fun clearQueue() {
        _state.update {
            QueueState(
                context = null,
                currentTrack = null,
                priorityQueue = emptyList(),
                history = emptyList(),
                shuffleEnabled = it.shuffleEnabled,
                repeatMode = it.repeatMode,
                shuffledContextIndices = null
            )
        }
    }

    /**
     * Retourne la vraie liste des pistes à venir du contexte, en respectant le mode shuffle.
     */
    fun getUpcomingContextTracks(): List<QueuedTrack> {
        val state = _state.value
        val ctx = state.context ?: return emptyList()
        val shuffled = state.shuffledContextIndices
        return if (shuffled != null) {
            val currentIndex = shuffled.indexOf(ctx.currentIndex)
            if (currentIndex >= 0 && currentIndex < shuffled.lastIndex) {
                 shuffled.drop(currentIndex + 1).map { ctx.tracks[it] }
            } else emptyList()
        } else {
            ctx.tracks.drop((ctx.currentIndex + 1).coerceAtLeast(0))
        }
    }

    /**
     * Retire une piste de la liste "À suivre", modifiant le contexte effectif ou le shuffle partiel.
     * La piste actuellement en cours de lecture ne peut jamais être supprimée par cet appel.
     */
    fun removeUpcomingContextTrack(internalId: String) {
        _state.update { current ->
            val ctx = current.context ?: return@update current
            if (ctx.tracks.isEmpty() || ctx.currentIndex !in ctx.tracks.indices) return@update current

            val trackIndex = ctx.tracks.indexOfFirst { it.internalId == internalId }
            // Interdiction de supprimer le morceau actuellement en cours de lecture
            if (trackIndex == -1 || trackIndex == ctx.currentIndex) return@update current
            
            val newTracks = ctx.tracks.toMutableList().apply { removeAt(trackIndex) }
            val newCurrentIndex = if (trackIndex < ctx.currentIndex) ctx.currentIndex - 1 else ctx.currentIndex
            
            val newShuffled = current.shuffledContextIndices?.mapNotNull {
                if (it == trackIndex) null
                else if (it > trackIndex) it - 1
                else it
            }
            
            current.copy(
                context = ctx.copy(tracks = newTracks, currentIndex = newCurrentIndex),
                shuffledContextIndices = newShuffled
            )
        }
    }

    /**
     * Réordonne la liste "À suivre".
     * En mode séquentiel comme en mode Shuffle, garantit que les mutations s'exécutent strictement
     * dans la fenêtre à venir (> currentIndex), préservant l'invariance absolue de la piste active.
     */
    fun reorderUpcomingContextTrack(fromInternalId: String, toInternalId: String) {
        _state.update { current ->
            val ctx = current.context ?: return@update current
            if (ctx.tracks.isEmpty() || ctx.currentIndex !in ctx.tracks.indices) return@update current
            if (fromInternalId == toInternalId) return@update current

            if (current.shuffledContextIndices != null) {
                // Mode Shuffle : permutation dans shuffledContextIndices
                val newShuffled = current.shuffledContextIndices.toMutableList()
                val fromArrayIdx = ctx.tracks.indexOfFirst { it.internalId == fromInternalId }
                val toArrayIdx = ctx.tracks.indexOfFirst { it.internalId == toInternalId }
                if (fromArrayIdx == -1 || toArrayIdx == -1) return@update current

                val fromShufflePos = newShuffled.indexOf(fromArrayIdx)
                val toShufflePos = newShuffled.indexOf(toArrayIdx)
                val currentShufflePos = newShuffled.indexOf(ctx.currentIndex)

                // Les deux éléments doivent appartenir strictement à la section à venir (> currentShufflePos)
                if (fromShufflePos <= currentShufflePos || toShufflePos <= currentShufflePos) return@update current

                val movedItem = newShuffled.removeAt(fromShufflePos)
                newShuffled.add(toShufflePos, movedItem)
                return@update current.copy(shuffledContextIndices = newShuffled)
            } else {
                // Mode Séquentiel standard
                val fromIdx = ctx.tracks.indexOfFirst { it.internalId == fromInternalId }
                val toIdx = ctx.tracks.indexOfFirst { it.internalId == toInternalId }
                if (fromIdx == -1 || toIdx == -1) return@update current

                // Les deux éléments doivent appartenir strictement à la section à venir (> currentIndex)
                if (fromIdx <= ctx.currentIndex || toIdx <= ctx.currentIndex) return@update current

                val newTracks = ctx.tracks.toMutableList()
                val movedItem = newTracks.removeAt(fromIdx)
                newTracks.add(toIdx, movedItem)

                // currentIndex reste parfaitement inchangé car la mutation a lieu strictement après
                return@update current.copy(context = ctx.copy(tracks = newTracks, currentIndex = ctx.currentIndex))
            }
        }
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
