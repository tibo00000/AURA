package com.aura.music.domain.player

/**
 * Etats principaux du player.
 * Gouverne par : docs/android/player/states-and-events.md
 */
enum class PlaybackState {
    Idle,
    Preparing,
    Playing,
    Paused,
    Buffering,
    Completed,
    Error,
}

/**
 * Modes de repetition.
 * Gouverne par : docs/domain/playback-model.md
 */
enum class RepeatMode {
    Off,
    One,
    All,
}

/**
 * Origine d'un track dans la file de lecture.
 * Un track vient soit du contexte source, soit de la priority queue.
 */
enum class TrackSource {
    CONTEXT,
    PRIORITY,
}

/**
 * Track en file de lecture avec son origine.
 */
data class QueuedTrack(
    val trackId: String,
    val title: String,
    val artistName: String,
    val albumTitle: String?,
    val contentUri: String?,
    val durationMs: Long?,
    val coverUri: String?,
    val source: TrackSource,
)

/**
 * Contexte de lecture actif.
 * Gouverne par : docs/domain/playback-model.md
 */
data class PlaybackContext(
    val type: String,
    val id: String,
    val tracks: List<QueuedTrack>,
    val currentIndex: Int,
) {
    val currentTrack: QueuedTrack?
        get() = tracks.getOrNull(currentIndex)
}

/**
 * Etat agrege expose a la couche UI.
 * Gouverne par : docs/android/player/states-and-events.md
 */
data class PlayerUiState(
    val playbackState: PlaybackState = PlaybackState.Idle,
    val currentTrack: QueuedTrack? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val priorityQueue: List<QueuedTrack> = emptyList(),
    val contextType: String? = null,
    val contextId: String? = null,
    val errorMessage: String? = null,
)
