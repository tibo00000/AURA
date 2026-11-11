package com.aura.music.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aura.music.core.AuthSessionManager

/** Runs on the player's looper. DataSource guards separately fence the loader immediately. */
internal class PrivatePlaybackGuard(private val player: Player, initialScope: AuthSessionManager.Scope?) {
    private var observed = initialScope
    fun onScopeChanged(scope: AuthSessionManager.Scope?) {
        if (scope == observed) return
        observed = scope
        if (isPrivate(player.currentMediaItem)) {
            player.playWhenReady = false
            player.stop() // Discard the loaded private source, decoders and buffers.
            player.clearMediaItems() // Do not retain an old private triplet for the next account.
        } else {
            // Keep an active local source playing; discard only queued/preloaded private items.
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (isPrivate(player.getMediaItemAt(index))) player.removeMediaItem(index)
            }
        }
    }
    private fun isPrivate(item: MediaItem?): Boolean {
        val scheme = item?.localConfiguration?.uri?.scheme
        return scheme.equals("https", true) || scheme.equals("http", true)
    }
}
