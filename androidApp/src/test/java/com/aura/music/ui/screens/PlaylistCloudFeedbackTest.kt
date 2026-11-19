package com.aura.music.ui.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.aura.music.ui.theme.AuraTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistCloudFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun feedbackShowsCountsAndBothDestinationsAtLargeFont() {
        var downloads = 0; var report = 0; var dismissed = 0
        lateinit var root: View
        compose.setContent {
            root = LocalView.current.rootView
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                AuraTheme {
                    Surface {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            PlaylistCloudFeedback(
                                "9 recherches demandées\n2 versions à identifier dans le bilan\n235 déjà disponibles",
                                { downloads++ }, { report++ }, { dismissed++ },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Voir les téléchargements").performClick()
        compose.onNodeWithText("Voir le bilan et identifier les versions").performClick()
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(1, report); assertEquals(0, dismissed) }
        System.getenv("AURA_UI_CAPTURE_DIR")?.let { directory ->
            compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(directory, "cloud-request-feedback-font130.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    @Test fun bulkConfirmationNamesItsCountAndOffersCancelBeforeStarting() {
        var confirmed = 0; var dismissed = 0
        compose.setContent {
            MaterialTheme {
                PlaylistSyncConfirmationDialog(
                    PlaylistSyncConfirmation(PlaylistSyncAction.RequestCloud, missingCount = 9),
                    { confirmed++ }, { dismissed++ },
                )
            }
        }
        compose.onNodeWithText("Demander 9 fichiers cloud ?").assertExists()
        compose.onNodeWithText("Annuler").performClick()
        compose.runOnIdle { assertEquals(0, confirmed); assertEquals(1, dismissed) }
    }
}
