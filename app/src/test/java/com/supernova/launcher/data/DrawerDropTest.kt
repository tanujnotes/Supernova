package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// A drawer folder dragged home lands as a folder of its own, in the spot the
// drop made room for, and a new item dropped on a taken dock slot sends the
// icon there to a free cell - or is refused when there is none.
class DrawerDropTest {

    private val cols = 5
    private val rows = 6

    private fun app(id: String, page: Int = 0, x: Int = 0, y: Int = 0) = LauncherItem(
        id = id, itemType = ItemType.APP, page = page, cellX = x, cellY = y,
        packageName = "p.$id", className = "c.$id"
    )

    private fun folder(x: Int, y: Int, container: ContainerType = ContainerType.WORKSPACE, rank: Int = 0) =
        LauncherItem(
            id = "new", itemType = ItemType.FOLDER, container = container, cellX = x, cellY = y,
            rank = rank, label = "Productivity"
        )

    private fun members(vararg ids: String) = ids.mapIndexed { rank, id ->
        LauncherItem(
            id = "m.$id", itemType = ItemType.APP, container = ContainerType.FOLDER, folderId = "new",
            rank = rank, packageName = "p.$id", className = "c.$id"
        )
    }

    private fun LauncherModel.item(id: String) = items.first { it.id == id }

    @Test
    fun folderLandsOnAFreeCellWithItsAppsInOrder() {
        val model = LauncherModel(items = listOf(app("a")))
        val result = LauncherRepository.withNewFolder(model, folder(2, 3), members("x", "y", "z"), emptyList(), cols, rows)
        val placed = result.item("new")
        assertEquals(ContainerType.WORKSPACE, placed.container)
        assertEquals(2 to 3, placed.cellX to placed.cellY)
        assertEquals("Productivity", placed.label)
        val inside = result.items.filter { it.folderId == "new" }.sortedBy { it.rank }
        assertEquals(listOf("m.x", "m.y", "m.z"), inside.map { it.id })
        assertTrue(inside.all { it.container == ContainerType.FOLDER })
    }

    @Test
    fun folderOnATakenCellNeverMergesAndMovesToTheNearestFreeOne() {
        val model = LauncherModel(items = listOf(app("a", x = 1, y = 1)))
        val result = LauncherRepository.withNewFolder(model, folder(1, 1), members("x"), emptyList(), cols, rows)
        // The icon it landed on is untouched, not folded into anything.
        assertEquals(ContainerType.WORKSPACE, result.item("a").container)
        assertEquals(null, result.item("a").folderId)
        val placed = result.item("new")
        assertTrue(placed.cellX to placed.cellY != 1 to 1)
    }

    @Test
    fun theNeighbourMakesRoomWhereThePreviewShowedIt() {
        val model = LauncherModel(items = listOf(app("a", x = 1, y = 1)))
        val moves = listOf(WorkspaceGeometry.ReorderMove("a", 1, 0))
        val result = LauncherRepository.withNewFolder(model, folder(1, 1), members("x"), moves, cols, rows)
        assertEquals(2 to 1, result.item("a").cellX to result.item("a").cellY)
        assertEquals(1 to 1, result.item("new").cellX to result.item("new").cellY)
    }

    @Test
    fun aFullPageTakesNoFolder() {
        val full = (0 until cols).flatMap { x -> (0 until rows).map { y -> app("$x.$y", x = x, y = y) } }
        val model = LauncherModel(items = full)
        val result = LauncherRepository.withNewFolder(model, folder(0, 0), members("x"), emptyList(), cols, rows)
        assertSame(model, result)
    }

    @Test
    fun aFolderTakesAnEmptyDockSlotWithItsApps() {
        val model = LauncherModel(items = listOf(app("d").copy(container = ContainerType.DOCK, rank = 0)))
        val dockFolder = folder(0, 0, ContainerType.DOCK, rank = 2)
        val result = LauncherRepository.withNewDockItem(model, dockFolder, members("x", "y"), 2, cols, rows, 0)
        assertEquals(ContainerType.DOCK, result.item("new").container)
        assertEquals(2, result.item("new").rank)
        assertEquals(0, result.item("d").rank)
        assertEquals(2, result.items.count { it.folderId == "new" })
    }

    @Test
    fun aTakenDockSlotSendsItsIconToAFreeCellOnThePageInView() {
        val model = LauncherModel(
            items = listOf(app("d").copy(container = ContainerType.DOCK, rank = 1), app("w", page = 1, x = 0, y = 0)),
            pageCount = 2
        )
        val shortcut = LauncherItem(
            id = "new", itemType = ItemType.SHORTCUT, container = ContainerType.DOCK, rank = 1,
            packageName = "p.s", shortcutId = "s"
        )
        val result = LauncherRepository.withNewDockItem(model, shortcut, emptyList(), 1, cols, rows, preferredPage = 1)
        val moved = result.item("d")
        assertEquals(ContainerType.WORKSPACE, moved.container)
        assertEquals(1, moved.page)
        assertTrue(moved.cellX to moved.cellY != 0 to 0)
        assertEquals(ContainerType.DOCK, result.item("new").container)
    }

    @Test
    fun aTakenDockSlotWithNoRoomAnywhereRefusesTheDrop() {
        val full = (0 until cols).flatMap { x -> (0 until rows).map { y -> app("$x.$y", x = x, y = y) } }
        val model = LauncherModel(items = full + app("d").copy(container = ContainerType.DOCK, rank = 0))
        val result = LauncherRepository.withNewDockItem(
            model, folder(0, 0, ContainerType.DOCK), members("x"), 0, cols, rows, 0
        )
        assertSame(model, result)
    }
}
