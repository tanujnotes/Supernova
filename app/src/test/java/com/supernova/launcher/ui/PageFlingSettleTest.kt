package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFlingSettleTest {

    private val page = 1080
    private val density = 2.625f

    private fun duration(distance: Int, velocity: Float) = PageFlingSettle.durationMs(distance, page, velocity, density)

    @Test
    fun aQuickFlickSettlesWellUnderTheOldCeiling() {
        // 700px flicked in 80ms leaves ~380px at ~9000px/s.
        val ms = duration(380, 9000f)
        assertTrue("was $ms", ms in 150..250)
    }

    @Test
    fun fasterFlingsSettleSooner() {
        assertTrue(duration(380, 15000f) < duration(380, 6000f))
    }

    @Test
    fun aSlowReleaseNeverTakesLongerThanBefore() {
        assertEquals(300, duration(480, 800f))
        assertEquals(300, duration(1080, 0f))
    }

    @Test
    fun nothingLeftMeansNoSettle() {
        assertEquals(0, duration(0, 9000f))
        assertEquals(0, PageFlingSettle.durationMs(100, 0, 9000f, density))
    }
}
