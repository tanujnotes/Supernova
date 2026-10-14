package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetCellsTest {

    // A 3x2 widget at the top left of the Galaxy's 5x6 home page.
    @Test
    fun fillsItsCellsOfTheCanvas() {
        assertEquals(0, widgetOrigin(0f, 5, 1014, 0))
        assertEquals(606, widgetSize(3f, 5, 1014, 0))
        assertEquals(486, widgetSize(2f, 6, 1462, 0))
    }

    // On a cold start the canvas is 1675px tall for a frame before the dock's
    // real height shrinks it to 1462px; the widget goes with it.
    @Test
    fun followsTheCanvasSize() {
        assertEquals(558, widgetSize(2f, 6, 1675, 0))
        assertEquals(486, widgetSize(2f, 6, 1462, 0))
        assertEquals(279 * 3, widgetOrigin(3f, 6, 1675, 0))
        assertEquals(243 * 3, widgetOrigin(3f, 6, 1462, 0))
    }

    // Whole-pixel cells, as drops count them: a drop on a widget's top-left
    // corner lands in the widget's own cell, even on the last column.
    @Test
    fun sharesItsCellsWithDrops() {
        for (cell in 0 until 5) {
            val x = widgetOrigin(cell.toFloat(), 5, 1014, 0).toFloat()
            val y = widgetOrigin(cell.toFloat(), 6, 1462, 0).toFloat()
            val p = workspacePlacement(1014, 1462, 5, 6, false, x, y)
            assertEquals(cell.toFloat(), p[0])
            assertEquals(cell.toFloat(), p[1])
        }
    }

    @Test
    fun insetsEverySide() {
        assertEquals(202 + 8, widgetOrigin(1f, 5, 1014, 8))
        assertEquals(404 - 16, widgetSize(2f, 5, 1014, 8))
    }

    @Test
    fun neverShrinksBelowAPixel() {
        assertEquals(1, widgetSize(1f, 5, 1014, 200))
    }

    // Mid-slide the resize frame and the widget sit on fractions of a cell,
    // and the slide can stop on any pixel on the way.
    @Test
    fun meetsTheResizeFrameAtEveryPixel() {
        for (px in 0..1014) {
            assertEquals(px, widgetOrigin(px / 202f, 5, 1014, 0))
        }
        for (px in 1..1014) {
            assertEquals(px, widgetSize(px / 202f, 5, 1014, 0))
        }
    }
}
