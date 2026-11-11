package com.supernova.launcher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSwipeGuardTest {

    private val slop = 24f

    private fun blocks(dx: Float, dy: Float) = PageSwipeGuard.blocksPageScroll(dx, dy, slop)

    @Test
    fun aSwipeDownThatDriftsSidewaysDoesNotTurnThePage() {
        // The report: a pull for the notification shade, a little off true.
        assertTrue(blocks(dx = 60f, dy = 500f))
        assertTrue(blocks(dx = -60f, dy = 500f))
        assertTrue(blocks(dx = 60f, dy = -500f))
    }

    @Test
    fun aLevelSwipeTurnsThePage() {
        assertFalse(blocks(dx = 300f, dy = 0f))
        assertFalse(blocks(dx = -300f, dy = 40f))
    }

    @Test
    fun aDiagonalUpToSixtyDegreesStillTurnsThePage() {
        // 45 degrees, and just inside 60.
        assertFalse(blocks(dx = 100f, dy = 100f))
        assertFalse(blocks(dx = 100f, dy = 170f))
        // Just past it.
        assertTrue(blocks(dx = 100f, dy = 176f))
    }

    @Test
    fun nothingToRefuseUntilThePagerWouldStart() {
        // Sideways travel inside the slop starts no scroll, however far the
        // finger has gone vertically.
        assertFalse(blocks(dx = slop, dy = 900f))
        assertFalse(blocks(dx = 0f, dy = 900f))
    }
}
