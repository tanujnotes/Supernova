package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Subgrid positioning for widgets: a widget can sit on, and span, half cells.
class WidgetHalfCellsTest {

    private fun widget(x: Int = 0, y: Int = 0, w: Int = 2, h: Int = 2, half: Boolean = false) =
        LauncherItem(
            id = "w", itemType = ItemType.WIDGET, cellX = x, cellY = y, spanX = w, spanY = h, halfCells = half
        )

    @Test
    fun halfCellsCountHalves() {
        val rect = WorkspaceGeometry.itemRect(widget(x = 1, y = 2, w = 5, h = 4, half = true), 5, 6)
        assertEquals(WorkspaceGeometry.CellRect(0.5f, 1f, 3f, 3f), rect)
    }

    @Test
    fun wholeRectStaysWholeCells() {
        val placed = WorkspaceGeometry.widgetOn(widget(half = true), WorkspaceGeometry.CellRect(1f, 2f, 3f, 4f))
        assertFalse(placed.halfCells)
        assertEquals(listOf(1, 2, 2, 2), listOf(placed.cellX, placed.cellY, placed.spanX, placed.spanY))
    }

    @Test
    fun halfRectGoesToHalfCells() {
        val placed = WorkspaceGeometry.widgetOn(widget(), WorkspaceGeometry.CellRect(1.5f, 2f, 3.5f, 4f))
        assertTrue(placed.halfCells)
        assertEquals(listOf(3, 4, 4, 4), listOf(placed.cellX, placed.cellY, placed.spanX, placed.spanY))
    }

    @Test
    fun resizeRoomCountsHalfSteps() {
        // A 2x2 widget at (0,0) on a 4x4 grid, an icon in the cell at (3,0).
        val icon = LauncherItem(id = "i", itemType = ItemType.APP, cellX = 3, cellY = 0)
        val model = LauncherModel(items = listOf(widget(), icon), pageCount = 1)
        val room = WorkspaceGeometry.resizeRoom(
            model, 0, "w", WorkspaceGeometry.CellRect(0f, 0f, 2f, 2f), 0.5f, 4, 4
        )
        assertEquals(WorkspaceGeometry.ResizeRoom(left = 0, top = 0, right = 2, bottom = 4), room)
    }

    @Test
    fun aRelocatedHalfCellWidgetLandsOnWholeCells() {
        val rect = WorkspaceGeometry.CellRect(0.5f, 0f, 3f, 2f)
        val moved = WorkspaceGeometry.displaced(rect, WorkspaceGeometry.ReorderMove("w", 1, 1, snapToCell = true))
        assertEquals(WorkspaceGeometry.cellRect(1, 1, 3, 2), moved)
    }
}
