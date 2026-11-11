package com.aura.music.ui.components

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aura.music.ui.theme.DarkGraphite
import com.aura.music.ui.theme.ElevatedGraphite

private val DefaultShimmerColors: List<Color> = listOf(
    DarkGraphite,
    ElevatedGraphite.copy(alpha = 0.8f),
    DarkGraphite
)

private val ShimmerAnimationSpec: InfiniteRepeatableSpec<Float> = infiniteRepeatable(
    animation = tween(durationMillis = 1100, easing = LinearEasing),
    repeatMode = RepeatMode.Restart
)

private val ShimmerShape4 = RoundedCornerShape(4.dp)
private val ShimmerShape6 = RoundedCornerShape(6.dp)
private val ShimmerShape8 = RoundedCornerShape(8.dp)
private val ShimmerShape12 = RoundedCornerShape(12.dp)
private val ShimmerShape16 = RoundedCornerShape(16.dp)

@Stable
internal class AnimatedShimmerBrush(
    private val translateState: State<Float>,
    private val shimmerColors: List<Color>
) : ShaderBrush() {
    private var lastTranslate: Float = Float.NaN
    private var cachedFrameBrush: FrameShimmerBrush? = null

    fun currentFrameBrush(): Brush {
        // Read translateState.value during the Draw phase (onDrawBehind) so each
        // DrawModifierNode subscribes to draw invalidations without recomposition.
        val translate = translateState.value
        val existing = cachedFrameBrush
        if (existing != null && translate == lastTranslate) {
            return existing
        }
        val nextBrush = FrameShimmerBrush(
            shimmerColors = shimmerColors,
            translate = translate
        )
        lastTranslate = translate
        cachedFrameBrush = nextBrush
        return nextBrush
    }

    override fun createShader(size: Size): Shader {
        val translate = translateState.value
        return LinearGradientShader(
            from = Offset.Zero,
            to = Offset(x = translate, y = translate),
            colors = shimmerColors
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnimatedShimmerBrush) return false
        return translateState == other.translateState && shimmerColors == other.shimmerColors
    }

    override fun hashCode(): Int {
        var result = translateState.hashCode()
        result = 31 * result + shimmerColors.hashCode()
        return result
    }
}

internal class FrameShimmerBrush(
    val shimmerColors: List<Color>,
    val translate: Float
) : ShaderBrush() {
    private var sharedShader: Shader? = null

    override fun createShader(size: Size): Shader {
        return sharedShader ?: LinearGradientShader(
            from = Offset.Zero,
            to = Offset(x = translate, y = translate),
            colors = shimmerColors
        ).also { sharedShader = it }
    }
}

/**
 * Creates a single shared linear gradient brush for shimmer animations.
 * Pass this brush down to list items or children to avoid creating multiple
 * InfiniteTransition instances in LazyLists.
 */
@Composable
fun rememberShimmerBrush(
    shimmerColors: List<Color> = DefaultShimmerColors
): Brush {
    val transition = rememberInfiniteTransition(label = "ShimmerTransition")
    val translateState = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = ShimmerAnimationSpec,
        label = "ShimmerTranslate"
    )

    return remember(translateState, shimmerColors) {
        AnimatedShimmerBrush(
            translateState = translateState,
            shimmerColors = shimmerColors
        )
    }
}

/**
 * Modifier to apply a background shimmer effect with a specific shape.
 */
fun Modifier.shimmer(
    brush: Brush,
    shape: Shape = ShimmerShape8
): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawBehind {
        val targetBrush = if (brush is AnimatedShimmerBrush) {
            brush.currentFrameBrush()
        } else {
            brush
        }
        drawOutline(outline = outline, brush = targetBrush)
    }
}

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
                .shimmer(brush, ShimmerShape6)
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
                    .shimmer(brush, ShimmerShape4)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(11.dp)
                    .shimmer(brush, ShimmerShape4)
            )
        }

        // Action placeholder
        Box(
            modifier = Modifier
                .size(24.dp)
                .shimmer(brush, ShimmerShape12)
        )
    }
}

/**
 * Reusable skeleton placeholder for a Card item (e.g. Playlist or Album).
 */
@Composable
fun ShimmerCard(
    modifier: Modifier = Modifier,
    brush: Brush = rememberShimmerBrush(),
    width: Dp? = null,
    height: Dp = 100.dp,
    shape: Shape = ShimmerShape16
) {
    val baseModifier = if (width != null) modifier.width(width) else modifier.fillMaxWidth()
    Box(
        modifier = baseModifier
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
 * Grid of skeleton cards for Library/Dashboard.
 */
@Composable
fun ShimmerGrid(
    modifier: Modifier = Modifier,
    rowCount: Int = 2,
    colCount: Int = 2,
    cardHeight: Dp = 80.dp
) {
    val brush = rememberShimmerBrush()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(rowCount) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(colCount) {
                    ShimmerCard(brush = brush, height = cardHeight, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
