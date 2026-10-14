package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The open choreography is a set of curves over progress. What matters is
// that both rest states are clean (nothing left half faded or shifted once
// the drawer is open or closed) and that each part moves one way between
// them.
class DrawerOpenCurveTest {
    private val steps = (0..100).map { it / 100f }

    @Test
    fun restStatesAreClean() {
        assertEquals(1f, DrawerOpenCurve.homeAlpha(0f), 0f)
        assertEquals(0f, DrawerOpenCurve.homeAlpha(1f), 0f)
        assertEquals(0f, DrawerOpenCurve.homeShift(0f), 0f)
        assertEquals(0f, DrawerOpenCurve.homeShift(1f), 0f)
        assertEquals(0f, DrawerOpenCurve.contentAlpha(0f), 0f)
        assertEquals(1f, DrawerOpenCurve.contentAlpha(1f), 0f)
        assertEquals(0f, DrawerOpenCurve.scrimAlpha(0f), 0f)
        assertEquals(DrawerOpenCurve.SCRIM_MAX, DrawerOpenCurve.scrimAlpha(1f), 0f)
    }

    @Test
    fun outOfRangeProgressClampsToTheRestStates() {
        assertEquals(1f, DrawerOpenCurve.homeAlpha(-0.5f), 0f)
        assertEquals(0f, DrawerOpenCurve.homeAlpha(1.5f), 0f)
        assertEquals(0f, DrawerOpenCurve.homeShift(-0.5f), 0f)
        assertEquals(0f, DrawerOpenCurve.homeShift(1.5f), 0f)
        assertEquals(0f, DrawerOpenCurve.contentAlpha(-1f), 0f)
        assertEquals(1f, DrawerOpenCurve.contentAlpha(2f), 0f)
        assertEquals(0f, DrawerOpenCurve.scrimAlpha(-1f), 0f)
        assertEquals(DrawerOpenCurve.SCRIM_MAX, DrawerOpenCurve.scrimAlpha(2f), 0f)
    }

    @Test
    fun desktopIsGoneBeforeContentArrives() {
        // The sheet never shows its content over a still-visible desktop.
        assertTrue(DrawerOpenCurve.HOME_FADE_END <= DrawerOpenCurve.CONTENT_FADE_START)
        assertEquals(0f, DrawerOpenCurve.homeAlpha(DrawerOpenCurve.HOME_FADE_END), 0f)
        assertEquals(0f, DrawerOpenCurve.contentAlpha(DrawerOpenCurve.CONTENT_FADE_START), 0f)
    }

    @Test
    fun desktopFadesFasterThanLinearAtFirst() {
        // About a quarter of the desktop drops in the first 5% of travel.
        assertTrue(DrawerOpenCurve.homeAlpha(DrawerOpenCurve.HOME_FADE_END * 0.15f) < 0.85f)
    }

    @Test
    fun eachPartMovesOneWay() {
        steps.zipWithNext { a, b ->
            assertTrue(DrawerOpenCurve.homeAlpha(b) <= DrawerOpenCurve.homeAlpha(a))
            assertTrue(DrawerOpenCurve.contentAlpha(b) >= DrawerOpenCurve.contentAlpha(a))
            assertTrue(DrawerOpenCurve.scrimAlpha(b) >= DrawerOpenCurve.scrimAlpha(a))
        }
    }

    @Test
    fun desktopDriftsOnlyWhileItCanBeSeen() {
        // Up with the finger until it has faded, then back to rest unseen.
        val peak = DrawerOpenCurve.homeShift(DrawerOpenCurve.HOME_FADE_END)
        assertTrue(peak > 0f)
        steps.forEach { p ->
            val shift = DrawerOpenCurve.homeShift(p)
            assertTrue(shift <= peak)
            if (p > DrawerOpenCurve.HOME_FADE_END && shift > 0f) {
                assertEquals(0f, DrawerOpenCurve.homeAlpha(p), 0f)
            }
        }
    }
}
