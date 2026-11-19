package com.aura.music.ui.screens

import android.app.Application
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aura.music.data.network.PlaylistTrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaylistCopiesDialogTest {
    @get:Rule val compose = createComposeRule()
    private val first = PlaylistTrackMetadata("deezer", "1", "Héra", "Georgio", trackId = "one")
    private val second = first.copy(providerId = "2", title = "Monde", trackId = "two")

    @Test fun confirmingOneCopyKeepsTheNextCopyAndErrorsInTheDialog() {
        var tracks by mutableStateOf(listOf(first, second))
        var busy by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var dismissed = 0
        var requests = 0
        compose.setContent {
            MaterialTheme {
                PlaylistCopiesDialog(tracks, 0, busy, error, rememberLazyListState(), {},
                    onReuse = { requests++; busy = true }, onSeparate = {}, onDismiss = { dismissed++ }) {
                    Text(it.title)
                }
            }
        }
        compose.onAllNodesWithText("Réutiliser")[0].performClick()
        compose.runOnIdle {
            assertEquals(1, requests); assertEquals(0, dismissed)
            tracks = listOf(second); busy = false
        }
        compose.onNodeWithText("Monde").assertExists()
        compose.onNodeWithText("Héra").assertDoesNotExist()
        compose.onNodeWithText("Réutiliser").performClick()
        compose.runOnIdle { error = "Réseau temporairement indisponible"; busy = false }
        compose.onNodeWithText("Réseau temporairement indisponible").assertExists()
        compose.onNodeWithText("Monde").assertExists()
        compose.onNodeWithText("Fermer").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun aTemporarilyHiddenDialogRestoresItsScrollPosition() {
        var hidden by mutableStateOf(false)
        lateinit var scroll: androidx.compose.foundation.lazy.LazyListState
        val tracks = (0..70).map { first.copy(trackId = "track:$it", title = "Title $it") }
        compose.setContent {
            scroll = rememberLazyListState(20)
            MaterialTheme {
                if (!hidden) PlaylistCopiesDialog(tracks, 1, false, null, scroll, {}, {}, {}, {}) { Text(it.title) }
            }
        }
        var position = 0
        compose.runOnIdle { position = scroll.firstVisibleItemIndex; hidden = true }
        compose.runOnIdle { hidden = false }
        compose.onNodeWithText("Copies possibles").assertExists()
        compose.runOnIdle { assertEquals(position, scroll.firstVisibleItemIndex) }
    }
}
