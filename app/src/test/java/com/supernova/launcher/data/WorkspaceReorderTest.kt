package com.supernova.launcher.data

import com.supernova.launcher.data.WorkspaceGeometry.ReorderMove
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceReorderTest {

    private fun icon(id: String, cellX: Int, cellY: Int) = LauncherItem(
        id = id, itemType = ItemType.APP, page = 0, cellX = cellX, cellY = cellY, label = id
    )

    private fun widget(id: String, cellX: Int, cellY: Int, spanX: Int, spanY: Int) = LauncherItem(
        id = id, itemType = ItemType.WIDGET, page = 0, cellX = cellX, cellY = cellY, spanX = spanX, spanY = spanY
    )

    private fun model(vararg items: LauncherItem) =
        LauncherModel(version = CURRENT_MODEL_VERSION, items = items.toList())

    private fun solve(
        model: LauncherModel,
        cellX: Int,
        cellY: Int,
        spanX: Int = 1,
        spanY: Int = 1,
        cols: Int = 5,
        rows: Int = 5,
        exclude: Set<String> = emptySet()
    ): List<ReorderMove>? = WorkspaceGeometry.solveReorder(
        model, 0, WorkspaceGeometry.cellRect(cellX, cellY, spanX, spanY), cols, rows, exclude
    )

    @Before
    fun resetOverlapPref() {
        WorkspaceGeometry.allowWidgetOverlap = false
    }

    @After
    fun restoreOverlapPref() {
        WorkspaceGeometry.allowWidgetOverlap = false
    }

    @Test
    fun freeTargetNeedsNoMoves() {
        assertEquals(emptyList<ReorderMove>(), solve(model(icon("a", 0, 0)), cellX = 3, cellY = 3))
    }

    @Test
    fun blockerIsPushedOneCellAside() {
        val moves = solve(model(icon("a", 2, 2)), cellX = 2, cellY = 2)
        assertEquals(listOf(ReorderMove("a", 1, 0)), moves)
    }

    @Test
    fun pushCascadesThroughNeighbours() {
        val moves = solve(model(icon("a", 2, 2), icon("b", 3, 2)), cellX = 2, cellY = 2)
        assertEquals(
            listOf(ReorderMove("a", 1, 0), ReorderMove("b", 1, 0)),
            moves?.sortedBy { it.itemId }
        )
    }

    @Test
    fun pushTriesAnotherDirectionAtTheGridEdge() {
        // 5x1 strip: c and d occupy the two rightmost cells, so pushing right
        // walks off the grid and the solver must push left instead.
        val moves = solve(model(icon("c", 3, 0), icon("d", 4, 0)), cellX = 3, cellY = 0, rows = 1)
        assertEquals(listOf(ReorderMove("c", -1, 0)), moves)
    }

    @Test
    fun widgetBlockerIsPushed() {
        val moves = solve(model(widget("w", 1, 1, 2, 2)), cellX = 1, cellY = 1)
        assertEquals(listOf(ReorderMove("w", 1, 0)), moves)
    }

    @Test
    fun widgetTargetPushesEverythingItCovers() {
        val moves = solve(model(icon("a", 1, 1), icon("b", 2, 1)), cellX = 1, cellY = 1, spanX = 2, spanY = 2)
        val byId = moves.orEmpty().associateBy { it.itemId }
        assertEquals(2, byId.size)
        // Both cleared the 2x2 target in the same direction.
        assertTrue(byId.values.all { it.dCellX == byId.getValue("a").dCellX && it.dCellY == byId.getValue("a").dCellY })
    }

    @Test
    fun draggedItemDoesNotBlockItsOwnTarget() {
        val moves = solve(model(icon("a", 2, 2)), cellX = 2, cellY = 2, exclude = setOf("a"))
        assertEquals(emptyList<ReorderMove>(), moves)
    }

    @Test
    fun relocationParksUnpushableBlockersOnFreeCells() {
        // A full-height 1x3 target column: the blocker can't clear it by
        // moving in any straight line (sides are occupied, top/bottom leave
        // the grid), so it must be relocated to a free cell instead.
        val m = model(icon("a", 0, 1), icon("b", 1, 1), icon("c", 2, 1))
        val moves = solve(m, cellX = 1, cellY = 0, spanY = 3, cols = 3, rows = 3)!!
        assertEquals(1, moves.size)
        val move = moves.single()
        assertEquals("b", move.itemId)
        assertTrue(move.snapToCell)
        val landing = (1 + move.dCellX) to (1 + move.dCellY)
        assertTrue(landing.first != 1) // outside the target column
        assertTrue(landing !in listOf(0 to 1, 2 to 1)) // not on a neighbour
    }

    @Test
    fun fullGridIsUnsolvable() {
        val m = model(icon("a", 0, 0), icon("b", 1, 0), icon("c", 2, 0))
        assertNull(solve(m, cellX = 1, cellY = 0, cols = 3, rows = 1))
    }

    @Test
    fun overlapExemptWidgetsAreNotBlockers() {
        WorkspaceGeometry.allowWidgetOverlap = true
        val moves = solve(model(widget("w", 0, 0, 2, 2)), cellX = 0, cellY = 0)
        assertEquals(emptyList<ReorderMove>(), moves)
    }

    @Test
    fun subgridIconAtHalfCellIsPushedClear() {
        // Icon centred on a half-cell boundary overlaps both cells 2 and 3 of
        // its row; clearing a drop on cell 2 must push it far enough that its
        // one-cell square no longer touches the target.
        val item = icon("a", 2, 2).copy(workspaceX = 0.5f, workspaceY = 0.5f) // centre (2.5, 2.5) on 5x5
        val moves = solve(model(item), cellX = 2, cellY = 2)!!
        assertEquals(1, moves.size)
        val d = moves.single()
        val rect = WorkspaceGeometry.itemRect(item, 5, 5)
        val movedLeft = rect.left + d.dCellX
        assertTrue(movedLeft >= 3f - 0.01f || rect.right + d.dCellX <= 2f + 0.01f)
    }
}
