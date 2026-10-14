package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

// Stacking on a page is list order; these guard what a widget's Bring to
// front and Send to back read (stacking) and do (restacked).
class WorkspaceStackingTest {

    private fun icon(id: String, cellX: Int, cellY: Int, page: Int = 0) = LauncherItem(
        id = id, itemType = ItemType.APP, page = page, cellX = cellX, cellY = cellY, label = id
    )

    private fun widget(id: String, cellX: Int, cellY: Int, spanX: Int, spanY: Int, page: Int = 0) = LauncherItem(
        id = id, itemType = ItemType.WIDGET, page = page, cellX = cellX, cellY = cellY, spanX = spanX, spanY = spanY
    )

    private fun model(vararg items: LauncherItem) =
        LauncherModel(version = CURRENT_MODEL_VERSION, items = items.toList())

    private fun stacking(model: LauncherModel, id: String) =
        WorkspaceGeometry.stacking(model, model.items.first { it.id == id }, 5, 6)

    @Test
    fun aWidgetOverlappingNothingHasNothingToRestack() {
        val m = model(widget("w", 0, 0, 2, 2), icon("a", 4, 4))
        assertEquals(WorkspaceGeometry.Stacking(below = false, above = false), stacking(m, "w"))
    }

    @Test
    fun overlapsReadFromListOrder() {
        // "under" comes first so it draws beneath; "over" comes last.
        val m = model(widget("under", 0, 0, 3, 2), widget("w", 1, 1, 2, 2), icon("over", 2, 2))
        assertEquals(WorkspaceGeometry.Stacking(below = true, above = true), stacking(m, "w"))
        assertEquals(WorkspaceGeometry.Stacking(below = false, above = true), stacking(m, "under"))
        assertEquals(WorkspaceGeometry.Stacking(below = true, above = false), stacking(m, "over"))
    }

    @Test
    fun edgeNeighboursAndOtherPagesDoNotCount() {
        // Sharing an edge is not an overlap, and a page only stacks its own
        // items; neither do dock or folder items.
        val m = model(
            widget("w", 1, 1, 2, 2),
            icon("beside", 3, 1),
            widget("elsewhere", 1, 1, 2, 2, page = 1),
            icon("docked", 1, 1).copy(container = ContainerType.DOCK)
        )
        assertEquals(WorkspaceGeometry.Stacking(below = false, above = false), stacking(m, "w"))
    }

    @Test
    fun freePositionedIconsOverlapByTheirOneCellSquare() {
        // An icon at a float centre covers the one-cell square around it.
        val m = model(icon("a", 0, 0).copy(workspaceX = 0.3f, workspaceY = 0.25f), widget("w", 1, 1, 2, 2))
        assertEquals(WorkspaceGeometry.Stacking(below = true, above = false), stacking(m, "w"))
    }

    @Test
    fun restackedMovesOnlyTheItem() {
        val items = listOf(icon("a", 0, 0), widget("w", 1, 1, 2, 2), icon("b", 4, 4), icon("c", 0, 5, page = 1))
        assertEquals(listOf("a", "b", "c", "w"), WorkspaceGeometry.restacked(items, "w", toFront = true).map { it.id })
        assertEquals(listOf("w", "a", "b", "c"), WorkspaceGeometry.restacked(items, "w", toFront = false).map { it.id })
        // An id that isn't there leaves the list as it was.
        assertEquals(items, WorkspaceGeometry.restacked(items, "missing", toFront = true))
    }

    @Test
    fun restackingChangesWhatTheMenuOffers() {
        val m = model(widget("w", 0, 0, 3, 2), widget("v", 1, 1, 2, 2))
        assertEquals(WorkspaceGeometry.Stacking(below = false, above = true), stacking(m, "w"))
        val front = m.copy(items = WorkspaceGeometry.restacked(m.items, "w", toFront = true))
        assertEquals(WorkspaceGeometry.Stacking(below = true, above = false), stacking(front, "w"))
    }
}
