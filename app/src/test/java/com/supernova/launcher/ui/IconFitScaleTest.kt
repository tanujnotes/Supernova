package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// An icon is the size its bar asks for until the cell cannot hold it with
// its padding, and it never grows. A label is never part of that (#383).
class IconFitScaleTest {

    // 48dp at 117% and 4dp padding on the Galaxy: a 157px icon keeping 11px.
    private val box = 157
    private val pad = 11

    @Test
    fun aRoomyCellKeepsTheIconsOwnSize() {
        // The Galaxy's 5 x 6 grid: 216 x 243px cells.
        assertEquals(box, fittedIconPx(216, 243, box, pad))
    }

    @Test
    fun aNarrowCellShrinksTheIconToItsWidth() {
        // 12 columns: 90px cells, less the padding either side.
        assertEquals(68, fittedIconPx(90, 400, box, pad))
    }

    @Test
    fun aShortCellShrinksTheIconToItsHeight() {
        assertEquals(79, fittedIconPx(300, 101, box, pad))
    }

    @Test
    fun theTighterSideWins() {
        assertEquals(68, fittedIconPx(90, 121, box, pad))
    }

    @Test
    fun labelsNeverChangeIt() {
        // Whatever the labels do, the same cell draws the same icon: the
        // label takes what the icon leaves.
        assertEquals(fittedIconPx(216, 243, box, pad), fittedIconPx(216, 243, box, pad))
        assertEquals(box, fittedIconPx(216, 400, box, pad))
        // A cell 194px wide inside its padding caps a bigger icon there.
        assertEquals(194, fittedIconPx(216, 243, 202, pad))
    }

    @Test
    fun noSizeYetLeavesTheIconAlone() {
        assertEquals(box, fittedIconPx(0, 0, box, pad))
        assertEquals(0, fittedIconPx(90, 120, 0, pad))
    }
}
