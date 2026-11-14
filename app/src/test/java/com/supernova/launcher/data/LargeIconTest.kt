package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Long-press > Large icon: a desktop icon or folder that takes 2x2 cells
// (LargeIcons.withIconSize, WorkspaceGeometry spans).
class LargeIconTest {

    private val cols = 5
    private val rows = 6

    private fun icon(id: String, cellX: Int, cellY: Int, span: Int = 1, type: ItemType = ItemType.APP) =
        LauncherItem(
            id = id, itemType = type, cellX = cellX, cellY = cellY, label = id, spanX = span, spanY = span,
            workspaceX = (cellX + span / 2f) / cols, workspaceY = (cellY + span / 2f) / rows
        )

    private fun model(vararg items: LauncherItem) =
        LauncherModel(version = CURRENT_MODEL_VERSION, items = items.toList())

    private fun LauncherModel.item(id: String) = items.first { it.id == id }

    private fun rect(m: LauncherModel, id: String) = WorkspaceGeometry.itemRect(m.item(id), cols, rows)

    @Test
    fun aLargeIconTakesItsTwoByTwoCells() {
        val m = model(icon("big", 1, 2, span = 2))
        assertEquals(WorkspaceGeometry.CellRect(1f, 2f, 3f, 4f), rect(m, "big"))
        assertFalse(WorkspaceGeometry.isAreaFree(m, 0, WorkspaceGeometry.cellRect(2, 3, 1, 1), cols, rows))
        assertTrue(WorkspaceGeometry.isAreaFree(m, 0, WorkspaceGeometry.cellRect(3, 3, 1, 1), cols, rows))
    }

    @Test
    fun aLargeIconOnItsGridIsLeftAlone() {
        val m = model(icon("big", 3, 4, span = 2), icon("a", 0, 0))
        assertSame(m, WorkspaceGeometry.fitted(m, cols, rows))
    }

    @Test
    fun aLargeIconStaysLargeOnASmallerGrid() {
        // Columns 3-4 are past a 4-column grid: it moves in as a 2x2.
        val m = model(icon("big", 3, 0, span = 2))
        val fitted = WorkspaceGeometry.fitted(m, 4, rows).item("big")
        assertEquals(2, fitted.spanX)
        assertEquals(2 to 0, fitted.cellX to fitted.cellY)
        assertEquals(3f / 4, fitted.workspaceX!!, 0.0001f)
    }

    @Test
    fun largeGrowsIntoFreeCellsFromItsOwn() {
        val m = model(icon("a", 1, 1))
        val next = LargeIcons.withIconSize(m, "a", large = true, cols, rows)!!.item("a")
        assertTrue(next.isLargeIcon)
        assertEquals(1 to 1, next.cellX to next.cellY)
        assertEquals(2f / cols, next.workspaceX!!, 0.0001f)
        assertEquals(2f / rows, next.workspaceY!!, 0.0001f)
    }

    @Test
    fun largeAtTheGridEdgeGrowsBackInward() {
        val m = model(icon("a", 4, 5))
        val next = LargeIcons.withIconSize(m, "a", large = true, cols, rows)!!.item("a")
        assertEquals(3 to 4, next.cellX to next.cellY)
    }

    @Test
    fun largePushesNeighboursAside() {
        val m = model(icon("a", 0, 0), icon("b", 1, 0), icon("c", 0, 1), icon("d", 1, 1))
        val next = LargeIcons.withIconSize(m, "a", large = true, cols, rows)!!
        assertEquals(0 to 0, next.item("a").let { it.cellX to it.cellY })
        val big = rect(next, "a")
        listOf("b", "c", "d").forEach { id ->
            val r = rect(next, id)
            val overlaps = r.left < big.right && big.left < r.right && r.top < big.bottom && big.top < r.bottom
            assertFalse(id, overlaps)
        }
    }

    @Test
    fun largeIsRefusedOnAFullPage() {
        val items = (0 until rows).flatMap { y -> (0 until cols).map { x -> icon("i$x$y", x, y) } }
        val m = model(*items.toTypedArray())
        assertNull(LargeIcons.withIconSize(m, "i00", large = true, cols, rows))
    }

    @Test
    fun normalKeepsTheTopLeftCell() {
        val m = model(icon("a", 2, 3, span = 2))
        val next = LargeIcons.withIconSize(m, "a", large = false, cols, rows)!!.item("a")
        assertFalse(next.isLargeIcon)
        assertEquals(2 to 3, next.cellX to next.cellY)
        assertEquals(2.5f / cols, next.workspaceX!!, 0.0001f)
    }

    @Test
    fun anIconLeavingTheDesktopIsMadeSmall() {
        val m = model(icon("a", 0, 0, span = 2).copy(container = ContainerType.DOCK, rank = 1))
        val next = LargeIcons.withSmallIconsOffDesktop(m).item("a")
        assertEquals(1, next.spanX)
        assertEquals(1, next.spanY)
        // Widgets and desktop icons keep their spans.
        val kept = model(icon("b", 0, 0, span = 2))
        assertSame(kept, LargeIcons.withSmallIconsOffDesktop(kept))
    }

    @Test
    fun aFolderLeftWithOneAppKeepsItsSize() {
        val folder = icon("f", 1, 1, span = 2, type = ItemType.FOLDER)
        val member = LauncherItem(
            id = "m", itemType = ItemType.APP, container = ContainerType.FOLDER, folderId = "f", label = "m"
        )
        val next = LauncherRepository.withSparseFolderDissolved(model(folder, member), "f").item("m")
        assertNotNull(next.workspaceX)
        assertTrue(next.isLargeIcon)
        assertEquals(1 to 1, next.cellX to next.cellY)
    }

    @Test
    fun aMovedIconCanStepAsATwoByTwo() {
        val m = model(icon("a", 0, 0, span = 2))
        val grid = AccessibleMoves.Grid(cols, rows, dockSlots = 5)
        val offered = AccessibleMoves.offered(m, m.item("a"), grid)
        assertTrue(AccessibleMoves.Move.RIGHT in offered)
        val target = AccessibleMoves.target(m, m.item("a"), AccessibleMoves.Move.RIGHT, grid, homePage = 0)
        assertEquals(AccessibleMoves.Target.Cell(0, 1, 0), target)
        val atEdge = model(icon("a", 3, 0, span = 2))
        assertFalse(AccessibleMoves.Move.RIGHT in AccessibleMoves.offered(atEdge, atEdge.item("a"), grid))
    }
}
