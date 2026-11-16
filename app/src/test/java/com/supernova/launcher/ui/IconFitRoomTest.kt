package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// When Settings' Icon size note says icons are held to their cells.
class IconFitRoomTest {

    // A 1080 x 1680px page at 2.8125 px/dp: 4dp padding is 11px, a label
    // line 45px.
    private val pad = 11
    private val label = 45

    @Test
    fun aRoomyGridFits() {
        // 5 x 6: 216 x 280px cells hold a 135px icon (48dp at 100%).
        assertFalse(IconFitRoom.desktopFitted(216, 280, 135, pad, label))
    }

    @Test
    fun manyRowsHoldTheIconBack() {
        // 6 x 10: 168px rows, under the 135 + 22 + 45 the icon needs.
        assertTrue(IconFitRoom.desktopFitted(180, 168, 135, pad, label))
        // The label's line is the difference.
        assertFalse(IconFitRoom.desktopFitted(180, 168, 135, pad, 0))
    }

    @Test
    fun manyColumnsHoldTheIconBack() {
        assertTrue(IconFitRoom.desktopFitted(120, 420, 135, pad, label))
    }

    @Test
    fun theFitScaleIsTheCellOverTheNeed() {
        // 202px needed in a 168px row.
        assertEquals(168f / 202f, IconFitRoom.desktopFitScale(180, 168, 135, pad, label), 0.0001f)
        assertEquals(1f, IconFitRoom.desktopFitScale(216, 280, 135, pad, label), 0f)
    }

    @Test
    fun drawerRowsShorterThanIconAndLabel() {
        // 7 columns of 154px hold a 120px icon; 1680px shared by 10 rows: 168px.
        assertTrue(IconFitRoom.drawerFitted(154, 11, 168, 120, 20 + label))
        assertFalse(IconFitRoom.drawerFitted(154, 11, 0, 120, 20 + label))
        assertFalse(IconFitRoom.drawerFitted(154, 11, 220, 120, 20 + label))
    }

    @Test
    fun drawerTileNarrowerThanTheIcon() {
        assertTrue(IconFitRoom.drawerFitted(154, 11, 0, 160, 0))
        assertFalse(IconFitRoom.drawerFitted(0, 11, 0, 160, 0))
    }
}
