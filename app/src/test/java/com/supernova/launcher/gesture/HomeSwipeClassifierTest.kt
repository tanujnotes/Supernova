package com.supernova.launcher.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSwipeClassifierTest {

    private val min = 60f
    private val bias = 1.2f

    private fun classify(
        dx: Float,
        dy: Float,
        canScroll: (Int) -> Boolean = { true }
    ): Gesture? = HomeSwipeClassifier.classify(dx, dy, min, bias, canScroll)

    @Test
    fun aDragPastTheThresholdIsASwipeWhateverItsSpeed() {
        // The whole point of the change: distance alone decides, so a drag
        // that paused before the lift counts like a flick.
        assertEquals(Gesture.SWIPE_DOWN, classify(dx = 0f, dy = 200f))
        assertEquals(Gesture.SWIPE_UP, classify(dx = 0f, dy = -200f))
    }

    @Test
    fun travelShorterThanTheThresholdIsNoSwipe() {
        assertNull(classify(dx = 0f, dy = 59f))
        assertNull(classify(dx = 59f, dy = 0f, canScroll = { false }))
    }

    @Test
    fun exactlyTheThresholdCounts() {
        assertEquals(Gesture.SWIPE_DOWN, classify(dx = 0f, dy = min))
    }

    @Test
    fun aDiagonalGoesToWhicheverAxisClearsTheBias() {
        assertEquals(Gesture.SWIPE_DOWN, classify(dx = 100f, dy = 140f))
        assertEquals(Gesture.SWIPE_RIGHT, classify(dx = 140f, dy = 100f, canScroll = { false }))
        // Neither axis clears the other by the bias: no swipe. A diagonal
        // sitting exactly ON the boundary is left untested on purpose - the
        // comparison is float arithmetic there (100f * 1.2f is 120.000002),
        // so which side it falls is rounding, not intent.
        assertNull(classify(dx = 100f, dy = 100f, canScroll = { false }))
    }

    @Test
    fun horizontalSwipesWaitForThePagerToRunOutOfPages() {
        // Swiping left asks for the next page (+1), right for the previous.
        assertNull(classify(dx = -200f, dy = 0f, canScroll = { it == 1 }))
        assertEquals(Gesture.SWIPE_LEFT, classify(dx = -200f, dy = 0f, canScroll = { it != 1 }))
        assertNull(classify(dx = 200f, dy = 0f, canScroll = { it == -1 }))
        assertEquals(Gesture.SWIPE_RIGHT, classify(dx = 200f, dy = 0f, canScroll = { it != -1 }))
    }

    @Test
    fun verticalSwipesIgnoreThePager() {
        assertEquals(Gesture.SWIPE_UP, classify(dx = 0f, dy = -200f, canScroll = { true }))
    }

    @Test
    fun aStationaryLiftIsNoSwipe() {
        assertNull(classify(dx = 0f, dy = 0f, canScroll = { false }))
    }
}
