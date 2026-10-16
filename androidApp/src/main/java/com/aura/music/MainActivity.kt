package com.aura.music

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.aura.music.domain.player.PlayerEvent
import com.aura.music.ui.AuraApp
import com.aura.music.ui.toQueuedTrack
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        setContent {
            AuraApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val appContainer = (application as? AuraApplication)?.container ?: return

        // 1. Spotify OAuth PKCE Deep Link Callback
        val uri = intent.data
        if (uri != null && uri.scheme == "aura" && uri.host == "spotify-callback") {
            lifecycleScope.launch {
                appContainer.spotifyAuthManager.handleAuthCallback(uri)
            }
            return
        }

        // 2. Media search / voice query support (Android Auto & Google Assistant)
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = intent.getStringExtra(SearchManager.QUERY)
                ?: intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE)
                ?: intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST)
                ?: ""

            lifecycleScope.launch {
                val tracks = if (query.isNotBlank()) {
                    appContainer.localLibraryRepository.searchLocalTracks(query, limit = 20)
                } else {
                    appContainer.localLibraryRepository.getRecentTracks(limit = 20).ifEmpty {
                        appContainer.localLibraryRepository.getAllTracks().take(20)
                    }
                }

                if (tracks.isNotEmpty()) {
                    val queuedTracks = tracks.map { it.toQueuedTrack() }
                    appContainer.playbackOrchestrator.onEvent(
                        PlayerEvent.PlayTrack(
                            trackId = queuedTracks.first().trackId,
                            contextType = "voice_search",
                            contextId = query.ifBlank { "recent" },
                            contextTracks = queuedTracks,
                            startIndex = 0
                        )
                    )
                }
            }
        }
    }
}
