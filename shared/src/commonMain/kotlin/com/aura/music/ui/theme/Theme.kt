package com.aura.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

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
    SystemAppearance()

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AuraTypography,
        content = content
    )
}
