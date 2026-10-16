package com.aura.music.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.music.desktop.ui.handClickable
import com.aura.music.ui.theme.BlazeOrange
import com.aura.music.ui.theme.PureWhite
import kotlinx.coroutines.delay

/**
 * Bouton Cœur interactif pour Desktop reproduisant fidèlement la micro-interaction
 * de l'application mobile AURA (rebond élastique Spring, transition de couleur et retour optimiste 0 ms).
 */
@Composable
fun DesktopFavoriteButton(
    isLiked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    isHovered: Boolean = true,
    hideWhenUnhovered: Boolean = false,
    size: Dp = 32.dp,
    iconSize: Dp = 20.dp,
    unlikedTint: Color = PureWhite.copy(alpha = 0.6f)
) {
    var optimisticLiked by remember(isLiked) { mutableStateOf(isLiked) }
    var triggerBounce by remember { mutableStateOf(false) }

    LaunchedEffect(isLiked) {
        optimisticLiked = isLiked
    }

    val scale by animateFloatAsState(
        targetValue = if (triggerBounce) 1.25f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "heartScale"
    )

    LaunchedEffect(triggerBounce) {
        if (triggerBounce) {
            delay(120)
            triggerBounce = false
        }
    }

    val targetColor = when {
        optimisticLiked -> BlazeOrange
        hideWhenUnhovered && !isHovered -> Color.Transparent
        else -> unlikedTint
    }

    val heartColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 150),
        label = "heartColor"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .handClickable {
                optimisticLiked = !optimisticLiked
                triggerBounce = true
                onToggle()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (optimisticLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (optimisticLiked) "Retirer des favoris" else "Ajouter aux favoris",
            tint = heartColor,
            modifier = Modifier.size(iconSize)
        )
    }
}
