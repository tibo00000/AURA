package com.aura.music.ui.components

import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ShimmerModifierTest {

    private class CountingFloatState(initial: Float) : State<Float> {
        var currentValue: Float = initial
        var readCount: Int = 0
            private set

        override val value: Float
            get() {
                readCount++
                return currentValue
            }
    }

    @Test
    fun `AnimatedShimmerBrush does not read translateState during construction`() {
        val state = CountingFloatState(120f)
        val colors = listOf(Color.DarkGray, Color.LightGray, Color.DarkGray)

        val brush = AnimatedShimmerBrush(translateState = state, shimmerColors = colors)

        // Zero reads during Composition / construction -> zero recomposition subscription
        assertEquals(0, state.readCount)
        assertEquals(brush, AnimatedShimmerBrush(translateState = state, shimmerColors = colors))
        assertEquals(0, state.readCount)
    }

    @Test
    fun `currentFrameBrush reads state on every draw call and memoizes brush per frame`() {
        val state = CountingFloatState(240f)
        val colors = listOf(Color.DarkGray, Color.LightGray, Color.DarkGray)
        val brush = AnimatedShimmerBrush(translateState = state, shimmerColors = colors)

        // Simulate 4 skeleton boxes drawing in Frame 1 (translate = 240f)
        val box1Brush = brush.currentFrameBrush() as FrameShimmerBrush
        val box2Brush = brush.currentFrameBrush() as FrameShimmerBrush
        val box3Brush = brush.currentFrameBrush() as FrameShimmerBrush
        val box4Brush = brush.currentFrameBrush() as FrameShimmerBrush

        // Every box must read state.value so its DrawModifierNode subscribes to draw invalidations
        assertEquals(4, state.readCount)

        // All 4 boxes in Frame 1 must reuse the exact same FrameShimmerBrush instance
        assertSame(box1Brush, box2Brush)
        assertSame(box1Brush, box3Brush)
        assertSame(box1Brush, box4Brush)
        assertEquals(240f, box1Brush.translate)
        assertEquals(colors, box1Brush.shimmerColors)

        // Advance animation to Frame 2 (translate = 480f)
        state.currentValue = 480f
        val nextFrameBox1Brush = brush.currentFrameBrush() as FrameShimmerBrush
        val nextFrameBox2Brush = brush.currentFrameBrush() as FrameShimmerBrush

        assertEquals(6, state.readCount)
        assertNotSame(box1Brush, nextFrameBox1Brush)
        assertSame(nextFrameBox1Brush, nextFrameBox2Brush)
        assertEquals(480f, nextFrameBox1Brush.translate)
    }
}
