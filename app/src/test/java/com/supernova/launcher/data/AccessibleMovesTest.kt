package com.supernova.launcher.data

import com.supernova.launcher.data.AccessibleMoves.Move
import com.supernova.launcher.data.AccessibleMoves.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibleMovesTest {

    private val dockSlots = 4
    private val grid = AccessibleMoves.Grid(cols = 4, rows = 5, dockSlots = dockSlots)

    private fun app(id: String, page: Int, x: Int, y: Int) = LauncherItem(
        id = id, itemType = ItemType.APP, container = ContainerType.WORKSPACE, page = page, cellX = x, cellY = y
    )

    private fun dock(id: String, rank: Int) = LauncherItem(
        id = id, itemType = ItemType.APP, container = ContainerType.DOCK, rank = rank
    )

    private fun target(model: LauncherModel, item: LauncherItem, move: Move, homePage: Int = 0) =
        AccessibleMoves.target(model, item, move, grid, homePage)

    @Test
    fun `a step lands on the free cell beside the icon`() {
        val icon = app("a", 0, 1, 1)
        val model = LauncherModel(items = listOf(icon))
        assertEquals(Target.Cell(0, 2, 1), target(model, icon, Move.RIGHT))
        assertEquals(Target.Cell(0, 1, 0), target(model, icon, Move.UP))
    }

    @Test
    fun `a step onto another icon doesn't happen, so nothing merges into a folder`() {
        val icon = app("a", 0, 1, 1)
        val model = LauncherModel(items = listOf(icon, app("b", 0, 2, 1)))
        assertNull(target(model, icon, Move.RIGHT))
    }

    @Test
    fun `moves off the grid or past the last page aren't offered`() {
        val corner = app("a", 0, 0, 0)
        val model = LauncherModel(pageCount = 1, items = listOf(corner))
        val offered = AccessibleMoves.offered(model, corner, grid)
        assertEquals(listOf(Move.RIGHT, Move.DOWN, Move.TO_DOCK), offered)
    }

    @Test
    fun `a page move takes the first free cell of that page`() {
        val icon = app("a", 0, 3, 3)
        val model = LauncherModel(pageCount = 2, items = listOf(icon, app("b", 1, 0, 0)))
        assertTrue(Move.NEXT_PAGE in AccessibleMoves.offered(model, icon, grid))
        assertEquals(Target.Cell(1, 1, 0), target(model, icon, Move.NEXT_PAGE))
    }

    @Test
    fun `the dock takes an icon in its first free slot, and none when it is full`() {
        val icon = app("a", 0, 0, 0)
        val roomy = LauncherModel(items = listOf(icon, dock("d0", 0), dock("d2", 2)))
        assertEquals(Target.Dock(1), target(roomy, icon, Move.TO_DOCK))

        val full = LauncherModel(items = listOf(icon) + (0 until dockSlots).map { dock("d$it", it) })
        assertNull(target(full, icon, Move.TO_DOCK))
    }

    @Test
    fun `a dock icon moves out to the page in view`() {
        val docked = dock("d", 0)
        val model = LauncherModel(pageCount = 2, items = listOf(docked, app("b", 1, 0, 0)))
        assertEquals(listOf(Move.TO_HOME), AccessibleMoves.offered(model, docked, grid))
        assertEquals(Target.Cell(1, 1, 0), target(model, docked, Move.TO_HOME, homePage = 1))
    }

    @Test
    fun `a widget is left to its own resize and move`() {
        val widget = LauncherItem(
            id = "w", itemType = ItemType.WIDGET, container = ContainerType.WORKSPACE, spanX = 2, spanY = 2
        )
        val model = LauncherModel(items = listOf(widget))
        assertEquals(emptyList<Move>(), AccessibleMoves.offered(model, widget, grid))
    }
}
