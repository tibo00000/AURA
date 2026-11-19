package com.aura.music.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
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
class PlaylistSyncUiTest {
    @get:Rule val compose = createComposeRule()

    private fun verifyConfirmation(confirmation: PlaylistSyncConfirmation, title: String, confirmLabel: String) {
        var pending by mutableStateOf<PlaylistSyncConfirmation?>(null)
        var effects = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
            MaterialTheme {
                Button(onClick = { pending = confirmation }) { Text("Ouvrir l'option") }
                pending?.let {
                    PlaylistSyncConfirmationDialog(it,
                        onConfirm = { effects++; pending = null }, onDismiss = { pending = null })
                }
            }
            }
        }
        compose.onNodeWithText("Ouvrir l'option").performClick()
        compose.onNodeWithText(title).assertExists()
        compose.runOnIdle { assertEquals(0, effects) }
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, effects) }
        compose.onNodeWithText("Ouvrir l'option").performClick()
        compose.onNodeWithText(confirmLabel).performClick()
        compose.runOnIdle { assertEquals(1, effects) }
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test fun detachOnlyRunsAfterExplicitConfirmation() = verifyConfirmation(
        PlaylistSyncConfirmation(PlaylistSyncAction.DetachSource, "Spotify"),
        "Retirer la source Spotify ?", "Retirer la source")

    @Test fun cloudRequestOnlyRunsAfterExplicitConfirmation() = verifyConfirmation(
        PlaylistSyncConfirmation(PlaylistSyncAction.RequestCloud),
        "Demander les fichiers cloud ?", "Demander les fichiers")

    @Test fun individualRetryAlsoNeedsConfirmation() = verifyConfirmation(
        PlaylistSyncConfirmation(PlaylistSyncAction.RequestCloud, retryTrackId = "track", trackTitle = "Coming Up Roses"),
        "Réessayer le téléchargement cloud ?", "Réessayer")

    @Test fun pullingRefreshesOnceAndDoesNotRepeatWhileRefreshing() {
        var refreshing by mutableStateOf(false)
        var calls = 0
        compose.setContent {
            MaterialTheme {
                PlaylistRefreshLayout(true, refreshing, { calls++; refreshing = true }, Modifier.testTag("playlist")) {
                    LazyColumn(Modifier.fillMaxSize()) { items(100) { Text("Piste $it", Modifier.height(56.dp)) } }
                }
            }
        }
        compose.onNodeWithTag("playlist").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("playlist").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, calls); refreshing = false }
        compose.onNodeWithTag("playlist").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun unassociatedPlaylistDoesNotStartASourceRefresh() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                PlaylistRefreshLayout(false, false, { calls++ }, Modifier.testTag("playlist")) {
                    LazyColumn(Modifier.fillMaxSize()) { items(10) { Text("Piste $it", Modifier.height(56.dp)) } }
                }
            }
        }
        compose.onNodeWithTag("playlist").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
