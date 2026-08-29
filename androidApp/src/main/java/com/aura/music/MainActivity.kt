package com.aura.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.aura.music.ui.AuraApp
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
        val uri = intent?.data ?: return
        if (uri.scheme == "aura" && uri.host == "spotify-callback") {
            val appContainer = (application as? AuraApplication)?.container
            if (appContainer != null) {
                lifecycleScope.launch {
                    appContainer.spotifyAuthManager.handleAuthCallback(uri)
                }
            }
        }
    }
}
