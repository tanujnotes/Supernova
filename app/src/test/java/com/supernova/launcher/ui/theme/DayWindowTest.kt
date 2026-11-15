package com.supernova.launcher.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayWindowTest {

    @Test
    fun aDaytimeWindowHoldsInsideIt() {
        assertTrue(DayWindow.contains(9 * 60, 8 * 60, 17 * 60))
        assertFalse(DayWindow.contains(17 * 60, 8 * 60, 17 * 60))
        assertFalse(DayWindow.contains(7 * 60, 8 * 60, 17 * 60))
    }

    @Test
    fun anOvernightWindowWrapsPastMidnight() {
        assertTrue(DayWindow.contains(23 * 60, 22 * 60, 6 * 60))
        assertTrue(DayWindow.contains(2 * 60, 22 * 60, 6 * 60))
        assertFalse(DayWindow.contains(12 * 60, 22 * 60, 6 * 60))
    }

    @Test
    fun aWindowWithNoLengthIsTheWholeDay() {
        assertTrue(DayWindow.contains(0, 300, 300))
        assertTrue(DayWindow.contains(1439, 300, 300))
    }
}
