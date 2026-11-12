package com.supernova.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// How the drawer's grid rows share out its height, and how a tile fits a row.
class DrawerTileFitTest {

    // A 48dp icon, 7dp gap and two 17dp label lines at 2.8125 px/dp, with
    // 12dp padding: 250px of content, 200px on one line, 33px padding.
    private val pad = 33
    private val content = 250
    private val compact = 200

    @Test
    fun autoKeepsTheTilesOwnHeight() {
        assertEquals(0, DrawerTileFit.rowHeight(heightPx = 2000, rows = 0, compactPx = compact, landscape = false))
        assertEquals(DrawerTileFit(pad, false, 1f), DrawerTileFit.of(0, pad, content, compact))
    }

    @Test
    fun everyRowSetFitsTheList() {
        // 12 rows in a 1900px list: none held back by the tile's own height.
        for (rows in listOf(4, 8, 12, 20)) {
            val row = DrawerTileFit.rowHeight(1900, rows, compact, landscape = false)
            assertEquals(rows, 1900 / row)
        }
    }

    @Test
    fun landscapeStopsAtOneLineTiles() {
        assertEquals(compact, DrawerTileFit.rowHeight(800, 12, compact, landscape = true))
        assertEquals(400, DrawerTileFit.rowHeight(800, 2, compact, landscape = true))
    }

    @Test
    fun roomyRowKeepsPadding() {
        assertEquals(DrawerTileFit(pad, false, 1f), DrawerTileFit.of(400, pad, content, compact))
    }

    @Test
    fun shorterRowGivesUpPaddingFirst() {
        // 20px spare: 10 each side, the rest of the padding goes.
        assertEquals(DrawerTileFit(10, false, 1f), DrawerTileFit.of(270, pad, content, compact))
        assertEquals(DrawerTileFit(0, false, 1f), DrawerTileFit.of(content, pad, content, compact))
    }

    @Test
    fun thenTheLabelGoesToOneLine() {
        val fit = DrawerTileFit.of(220, pad, content, compact)
        assertTrue(fit.singleLine)
        assertEquals(1f, fit.scale)
        assertEquals(0, fit.paddingPx)
    }

    @Test
    fun thenIconAndLabelShrinkToTheRow() {
        val fit = DrawerTileFit.of(150, pad, content, compact)
        assertTrue(fit.singleLine)
        assertEquals(0.75f, fit.scale, 0.0001f)
        // Scaled, the one-line tile is the row's height.
        assertEquals(150f, compact * fit.scale, 0.5f)
    }

    @Test
    fun oneLineLabelsShrinkStraightAway() {
        // Labels already on one line, or hidden: content and compact match.
        val fit = DrawerTileFit.of(100, pad, compact, compact)
        assertEquals(0.5f, fit.scale, 0.0001f)
    }

    @Test
    fun unmeasuredTileIsLeftAlone() {
        val fit = DrawerTileFit.of(100, pad, 0, 0)
        assertFalse(fit.singleLine)
        assertEquals(1f, fit.scale)
        assertEquals(pad, fit.paddingPx)
    }
}
