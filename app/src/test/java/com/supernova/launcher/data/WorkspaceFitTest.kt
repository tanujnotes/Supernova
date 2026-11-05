package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

// A grid change keeps stored spots and fits what no longer lines up, for
// showing and for placement checks (WorkspaceGeometry.fitted).
class WorkspaceFitTest {

    // A free-positioned icon on its cell, as a drop on a cols x rows grid
    // stores it.
    private fun icon(id: String, cellX: Int, cellY: Int, cols: Int, rows: Int, page: Int = 0) = LauncherItem(
        id = id, itemType = ItemType.APP, page = page, cellX = cellX, cellY = cellY, label = id,
        workspaceX = (cellX + 0.5f) / cols, workspaceY = (cellY + 0.5f) / rows
    )

    private fun widget(id: String, cellX: Int, cellY: Int, spanX: Int, spanY: Int) = LauncherItem(
        id = id, itemType = ItemType.WIDGET, cellX = cellX, cellY = cellY, spanX = spanX, spanY = spanY
    )

    private fun model(vararg items: LauncherItem) =
        LauncherModel(version = CURRENT_MODEL_VERSION, items = items.toList())

    private fun cellOf(model: LauncherModel, id: String) =
        model.items.first { it.id == id }.let { it.cellX to it.cellY }

    @Test
    fun aLayoutOnItsOwnGridIsLeftAlone() {
        val m = model(icon("a", 0, 0, 5, 6), icon("b", 4, 5, 5, 6), widget("w", 1, 1, 3, 2))
        assertSame(m, WorkspaceGeometry.fitted(m, 5, 6))
    }

    @Test
    fun anIconOffASmallerGridTakesTheNearestFreeCell() {
        // Column 10 of 12 is past a 5-column grid; its old cell is gone.
        val m = model(icon("a", 0, 0, 12, 12), icon("far", 10, 3, 12, 12))
        val fitted = WorkspaceGeometry.fitted(m, 5, 6)
        val (x, y) = cellOf(fitted, "far")
        assertEquals(4, x)
        assertEquals(1, y)
        // Stored spots stay as they were.
        assertEquals(10 to 3, cellOf(m, "far"))
    }

    @Test
    fun anIconThatStillFitsKeepsItsCell() {
        // Set on 6 columns, cell 2 is still a cell on 5: kept there, not
        // left between two cells.
        val m = model(icon("a", 2, 1, 6, 6))
        val fitted = WorkspaceGeometry.fitted(m, 5, 6)
        assertEquals(2 to 1, cellOf(fitted, "a"))
        assertEquals(2.5f / 5, fitted.items.first().workspaceX!!, 0.0001f)
    }

    @Test
    fun aTakenCellSendsTheIconToAFreeOne() {
        val m = model(icon("a", 2, 1, 5, 6), icon("b", 2, 1, 6, 6))
        val fitted = WorkspaceGeometry.fitted(m, 5, 6)
        assertEquals(2 to 1, cellOf(fitted, "a"))
        assertFalse(cellOf(fitted, "b") == (2 to 1))
    }

    @Test
    fun aWidgetHangingOverTheEdgeIsPulledInside() {
        val m = model(widget("w", 3, 0, 3, 2))
        val fitted = WorkspaceGeometry.fitted(m, 5, 6)
        assertEquals(2 to 0, cellOf(fitted, "w"))
    }

    @Test
    fun placementChecksSeeTheFittedSpot() {
        val m = model(icon("far", 10, 0, 12, 12))
        // Shown at the top right cell of a 5x6 grid, so that cell is taken.
        assertFalse(WorkspaceGeometry.isAreaFree(m, 0, WorkspaceGeometry.cellRect(4, 0, 1, 1), 5, 6))
    }
}
