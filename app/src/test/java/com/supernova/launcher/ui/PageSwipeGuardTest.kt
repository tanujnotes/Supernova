package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageSwipeGuardTest {

    private val slop = 24f

    private fun vertical(dx: Float, dy: Float) = PageSwipeGuard.isVerticalSwipe(dx, dy, slop)

    @Test
    fun aSwipeDownThatDriftsSidewaysIsVertical() {
        // The report: a pull for the notification shade, a little off true.
        assertEquals(true, vertical(dx = 8f, dy = 30f))
        assertEquals(true, vertical(dx = -8f, dy = 30f))
        assertEquals(true, vertical(dx = 8f, dy = -30f))
        // Anything steeper than the diagonal, as Launcher3's pulls take it.
        assertEquals(true, vertical(dx = 20f, dy = 25f))
    }

    @Test
    fun aFlatterStartIsThePagers() {
        assertEquals(false, vertical(dx = 30f, dy = 0f))
        assertEquals(false, vertical(dx = -30f, dy = 10f))
        assertEquals(false, vertical(dx = 25f, dy = 25f))
    }

    @Test
    fun nothingIsSettledInsideTheSlop() {
        assertNull(vertical(dx = 0f, dy = 0f))
        assertNull(vertical(dx = slop, dy = slop))
        assertNull(vertical(dx = -10f, dy = 20f))
    }
}
