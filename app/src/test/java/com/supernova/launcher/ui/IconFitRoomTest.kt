package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// When Settings' Icon size note says icons are held to their cells. Labels
// are not in any of it: an icon is the size its bar asks for whether labels
// show or not (#383).
class IconFitRoomTest {

    // A 1080 x 1680px page at 2.8125 px/dp: 4dp padding is 11px.
    private val pad = 11

    @Test
    fun aRoomyGridFits() {
        // 5 x 6: 216 x 280px cells hold a 135px icon (48dp at 100%).
        assertFalse(IconFitRoom.desktopFitted(216, 280, 135, pad))
    }

    @Test
    fun manyRowsHoldTheIconBack() {
        // 6 x 14: 120px rows, under the 135 + 22 the icon needs.
        assertTrue(IconFitRoom.desktopFitted(180, 120, 135, pad))
        // A label line of its own would not have changed that either way:
        // 168px rows hold the same icon.
        assertFalse(IconFitRoom.desktopFitted(180, 168, 135, pad))
    }

    @Test
    fun manyColumnsHoldTheIconBack() {
        assertTrue(IconFitRoom.desktopFitted(120, 420, 135, pad))
    }

    @Test
    fun theFitScaleIsTheCellOverTheNeed() {
        // A 135px icon keeping 11px in a 120px row: 98px of it.
        assertEquals(98f / 135f, IconFitRoom.desktopFitScale(180, 120, 135, pad), 0.0001f)
        assertEquals(1f, IconFitRoom.desktopFitScale(216, 280, 135, pad), 0f)
    }

    @Test
    fun drawerRowsShorterThanTheIcon() {
        // 7 columns of 154px hold a 120px icon; a 100px row does not.
        assertTrue(IconFitRoom.drawerFitted(154, 11, 100, 120))
        assertFalse(IconFitRoom.drawerFitted(154, 11, 0, 120))
        assertFalse(IconFitRoom.drawerFitted(154, 11, 168, 120))
    }

    @Test
    fun drawerTileNarrowerThanTheIcon() {
        assertTrue(IconFitRoom.drawerFitted(154, 11, 0, 160))
        assertFalse(IconFitRoom.drawerFitted(0, 11, 0, 160))
    }
}
