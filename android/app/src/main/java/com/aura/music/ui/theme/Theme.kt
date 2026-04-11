package com.aura.music.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = BlazeOrange,
    onPrimary = TextOnAccent,
    primaryContainer = BurntOrange,
    onPrimaryContainer = TextPrimary,
    secondary = AmberGlow,
    onSecondary = TextOnAccent,
    background = DeepBlack,
    onBackground = TextPrimary,
    surface = OffBlack,
    onSurface = TextPrimary,
    surfaceVariant = DarkGraphite,
    onSurfaceVariant = TextSecondary,
    error = SemanticError,
    onError = TextOnAccent
)

@Composable
fun AuraTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val context = view.context
            if (context is Activity) {
                val window = context.window
                window.statusBarColor = DeepBlack.toArgb()
                window.navigationBarColor = DeepBlack.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AuraTypography,
        content = content
    )
}
