package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

// An icon shrinks into a cell smaller than the room it needs, and never grows.
class IconFitScaleTest {

    @Test
    fun aRoomyCellKeepsTheIconsOwnSize() {
        // The Galaxy's 5 x 6 grid: 216 x 243px cells, a 157 x 202px icon.
        assertEquals(1f, iconFitScale(216, 243, 157, 202), 0f)
    }

    @Test
    fun aNarrowCellShrinksTheIconToItsWidth() {
        // 12 columns: 90px cells. The icon and its padding fit the width.
        assertEquals(90f / 157f, iconFitScale(90, 400, 157, 202), 1e-6f)
    }

    @Test
    fun aShortCellShrinksTheIconToItsHeight() {
        // Landscape rows: icon and label fit the height.
        assertEquals(0.5f, iconFitScale(300, 101, 157, 202), 1e-6f)
    }

    @Test
    fun theTighterSideWins() {
        assertEquals(90f / 157f, iconFitScale(90, 121, 157, 202), 1e-6f)
    }

    @Test
    fun noSizeYetLeavesTheIconAlone() {
        assertEquals(1f, iconFitScale(0, 0, 157, 202), 0f)
        assertEquals(1f, iconFitScale(90, 120, 0, 0), 0f)
    }
}
