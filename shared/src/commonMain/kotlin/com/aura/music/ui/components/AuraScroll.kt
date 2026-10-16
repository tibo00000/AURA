package com.aura.music.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Standard fling behavior for all scrollable surfaces in AURA.
 * - maxVelocity: 6000f (px/s)
 * - frictionMultiplier: 0.5f (smooth, natural glide)
 */
@Composable
fun rememberAuraFlingBehavior(
    maxVelocity: Float = 6000f,
    frictionMultiplier: Float = 0.5f
): FlingBehavior {
    val decayAnimationSpec = remember(frictionMultiplier) {
        exponentialDecay<Float>(frictionMultiplier = frictionMultiplier)
    }
    return remember(maxVelocity, decayAnimationSpec) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val clampedVelocity = initialVelocity.coerceIn(-maxVelocity, maxVelocity)
                if (abs(clampedVelocity) < 1f) {
                    return 0f
                }

                var lastValue = 0f
                var remainingVelocity = clampedVelocity

                AnimationState(
                    initialValue = 0f,
                    initialVelocity = clampedVelocity
                ).animateDecay(decayAnimationSpec) {
                    val delta = value - lastValue
                    val consumed = scrollBy(delta)
                    lastValue = value
                    remainingVelocity = velocity
                    if (abs(delta - consumed) > 0.5f) {
                        cancelAnimation()
                    }
                }
                return remainingVelocity
            }
        }
    }
}

/**
 * Standard LazyColumn in AURA with default [rememberAuraFlingBehavior].
 */
@Composable
fun AuraLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    val flingBehavior = rememberAuraFlingBehavior()
    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content
    )
}
