package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The Adaptive icon launch's timeline, a port of Launcher3 QuickStep's
// app-open curves: which way the icon travels, how long each part takes, and
// when the app takes over.
class IconLiftTest {

    // A 1080x2340 screen and a 150px icon, as on the test phone.
    private fun lift(left: Float, top: Float, cellHeight: Float = 280f) =
        IconLift(left, top, 150f, 150f, 1080f, 2340f, cellHeight)

    @Test
    fun anIconInTheLowerHalfRisesOnTheLongCurve() {
        val dock = lift(left = 60f, top = 2000f)
        assertTrue(dock.upward)
        assertEquals(250f, dock.xMs)
        assertEquals(450f, dock.yMs)
        assertEquals(50f, dock.fadeMs)
        // 25ms in, the icon starts to fade, and at 87% gone the app is asked for.
        assertEquals(68.5f, dock.handOffMs, 0.001f)
    }

    @Test
    fun anIconHighUpDropsOnShorterCurves() {
        val top = lift(left = 60f, top = 200f)
        assertFalse(top.upward)
        assertEquals(360f, top.xMs)
        assertEquals(200f, top.yMs)
        assertEquals(40f, top.fadeMs)
        assertEquals(59.8f, top.handOffMs, 0.001f)
    }

    @Test
    fun anIconJustAboveTheMiddleStillRises() {
        // Its center is 100px above the screen's, under a cell's height.
        assertTrue(lift(left = 465f, top = 995f).upward)
        assertFalse(lift(left = 465f, top = 995f, cellHeight = 90f).upward)
    }

    @Test
    fun theIconHeadsForTheCenterAndGrowsToCoverTheShortSide() {
        val icon = lift(left = 60f, top = 2000f)
        assertEquals(540f - 135f, icon.dx, 0.001f)
        assertEquals(1170f - 2075f, icon.dy, 0.001f)
        assertEquals(1080f / 150f, icon.endScale, 0.001f)
    }

    @Test
    fun theFadeWaitsThenRunsLinearly() {
        val icon = lift(left = 60f, top = 2000f)
        assertEquals(1f, icon.alpha(0f), 0f)
        assertEquals(1f, icon.alpha(25f), 0f)
        assertEquals(0.5f, icon.alpha(50f), 0.001f)
        assertEquals(0f, icon.alpha(75f), 0f)
        assertTrue(icon.alpha(icon.handOffMs + 0.1f) < IconLift.HAND_OFF_ALPHA)
    }

    @Test
    fun eachPartsProgressStopsAtItsEnd() {
        val icon = lift(left = 60f, top = 2000f)
        assertEquals(0.5f, icon.xProgress(125f), 0.001f)
        assertEquals(1f, icon.xProgress(300f), 0f)
        assertEquals(0.5f, icon.yProgress(225f), 0.001f)
        assertEquals(1f, icon.scaleProgress(IconLift.TOTAL_MS), 0f)
        assertEquals(0f, icon.scaleProgress(-5f), 0f)
    }
}
