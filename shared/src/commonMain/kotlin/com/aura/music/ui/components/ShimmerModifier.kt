package com.aura.music.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.ElevatedGraphite

/**
 * Creates a single shared linear gradient brush for shimmer animations.
 * Pass this brush down to list items or children to avoid creating multiple
 * InfiniteTransition instances in LazyLists.
 */
@Composable
fun rememberShimmerBrush(
    shimmerColors: List<Color> = listOf(
        DarkGraphite,
        ElevatedGraphite.copy(alpha = 0.8f),
        DarkGraphite
    )
): Brush {
    val transition = rememberInfiniteTransition(label = "ShimmerTransition")
    val translateAnimation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerTranslate"
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnimation, y = translateAnimation)
    )
}

/**
 * Modifier to apply a background shimmer effect with a specific shape.
 */
fun Modifier.shimmer(
    brush: Brush,
    shape: Shape = RoundedCornerShape(8.dp)
): Modifier = this
    .clip(shape)
    .background(brush)

/**
 * Reusable skeleton placeholder for a Track row item.
 */
@Composable
fun ShimmerTrackRow(
    brush: Brush,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Thumbnail placeholder
        Box(
            modifier = Modifier
                .size(48.dp)
                .shimmer(brush, RoundedCornerShape(6.dp))
        )

        // Title and Subtitle bars
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(14.dp)
                    .shimmer(brush, RoundedCornerShape(4.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(11.dp)
                    .shimmer(brush, RoundedCornerShape(4.dp))
            )
        }

        // Action placeholder
        Box(
            modifier = Modifier
                .size(24.dp)
                .shimmer(brush, RoundedCornerShape(12.dp))
        )
    }
}

/**
 * Reusable skeleton placeholder for a Card item (e.g. Playlist or Album).
 */
@Composable
fun ShimmerCard(
    brush: Brush,
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
    shape: Shape = RoundedCornerShape(16.dp)
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .shimmer(brush, shape)
    )
}

/**
 * Vertical list of Track Row skeletons with a single shared brush.
 */
@Composable
fun ShimmerTrackList(
    count: Int = 6,
    modifier: Modifier = Modifier
) {
    val brush = rememberShimmerBrush()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(count) {
            ShimmerTrackRow(brush = brush)
        }
    }
}

/**
 * Grid of 2x2 or 2xN skeleton cards for Library/Dashboard.
 */
@Composable
fun ShimmerGrid(
    count: Int = 4,
    modifier: Modifier = Modifier
) {
    val brush = rememberShimmerBrush()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val rowCount = (count + 1) / 2
        for (i in 0 until rowCount) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShimmerCard(brush = brush, height = 76.dp, modifier = Modifier.weight(1f))
                if (i * 2 + 1 < count) {
                    ShimmerCard(brush = brush, height = 76.dp, modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
