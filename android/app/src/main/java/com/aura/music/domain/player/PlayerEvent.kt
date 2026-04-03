package com.aura.music.domain.player

/**
 * Evenements utilisateur adresses au player.
 * Gouverne par : docs/android/player/states-and-events.md
 */
sealed interface PlayerEvent {
    data class PlayTrack(
        val trackId: String,
        val contextType: String,
        val contextId: String,
        val contextTracks: List<QueuedTrack>,
        val startIndex: Int,
    ) : PlayerEvent

    data object Pause : PlayerEvent
    data object TogglePlayPause : PlayerEvent
    data object Next : PlayerEvent
    data object Previous : PlayerEvent
    data class SeekTo(val positionMs: Long) : PlayerEvent
    data class AddToQueue(val track: QueuedTrack) : PlayerEvent
    data class RemoveFromQueue(val index: Int) : PlayerEvent
    data class ReorderQueue(val fromIndex: Int, val toIndex: Int) : PlayerEvent
    data object ToggleShuffle : PlayerEvent
    data object CycleRepeatMode : PlayerEvent
}
