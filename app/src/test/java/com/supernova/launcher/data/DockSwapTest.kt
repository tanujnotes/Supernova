package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

// A drop on an occupied dock slot trades the two items' places, whatever the
// dropped one came from, and never parks the displaced icon at a page cell
// left over from long ago.
class DockSwapTest {

    private fun app(id: String, container: ContainerType, page: Int = 0, x: Int = 0, y: Int = 0, rank: Int = 0, folder: String? = null) =
        LauncherItem(
            id = id, itemType = ItemType.APP, container = container, page = page, cellX = x, cellY = y,
            rank = rank, folderId = folder, packageName = "p.$id", className = "c.$id"
        )

    private fun LauncherModel.item(id: String) = items.first { it.id == id }

    @Test
    fun dockToDockTradesSlots() {
        // A dock icon keeps whatever page and cell it had before it was
        // docked; they must not be where the displaced icon goes.
        val model = LauncherModel(
            items = listOf(
                app("a", ContainerType.DOCK, page = 3, x = 2, y = 2, rank = 0),
                app("b", ContainerType.DOCK, rank = 1)
            ),
            pageCount = 1
        )
        val moved = LauncherRepository.movedToDock(model, "a", 1)
        assertEquals(ContainerType.DOCK, moved.item("a").container)
        assertEquals(1, moved.item("a").rank)
        assertEquals(ContainerType.DOCK, moved.item("b").container)
        assertEquals(0, moved.item("b").rank)
    }

    @Test
    fun pageToDockSendsTheDisplacedIconToTheFreedCell() {
        val model = LauncherModel(
            items = listOf(app("w", ContainerType.WORKSPACE, page = 1, x = 2, y = 3), app("d", ContainerType.DOCK, rank = 0)),
            pageCount = 2
        )
        val moved = LauncherRepository.movedToDock(model, "w", 0)
        assertEquals(ContainerType.DOCK, moved.item("w").container)
        val displaced = moved.item("d")
        assertEquals(ContainerType.WORKSPACE, displaced.container)
        assertEquals(1, displaced.page)
        assertEquals(2, displaced.cellX)
        assertEquals(3, displaced.cellY)
    }

    @Test
    fun folderToDockPutsTheDisplacedIconInTheFolder() {
        val folder = LauncherItem(id = "f", itemType = ItemType.FOLDER, page = 0, cellX = 1, cellY = 1, label = "F")
        val model = LauncherModel(
            items = listOf(
                folder,
                app("m", ContainerType.FOLDER, rank = 2, folder = "f"),
                app("d", ContainerType.DOCK, rank = 0)
            )
        )
        val moved = LauncherRepository.movedToDock(model, "m", 0)
        assertEquals(ContainerType.DOCK, moved.item("m").container)
        assertNull(moved.item("m").folderId)
        val displaced = moved.item("d")
        assertEquals(ContainerType.FOLDER, displaced.container)
        assertEquals("f", displaced.folderId)
        assertEquals(2, displaced.rank)
    }

    @Test
    fun aDockedFolderIsNeverPushedIntoAnotherFolder() {
        val model = LauncherModel(
            items = listOf(
                LauncherItem(id = "f", itemType = ItemType.FOLDER, label = "F"),
                app("m", ContainerType.FOLDER, rank = 0, folder = "f"),
                LauncherItem(id = "g", itemType = ItemType.FOLDER, container = ContainerType.DOCK, rank = 0, label = "G")
            )
        )
        assertSame(model, LauncherRepository.movedToDock(model, "m", 0))
    }

    @Test
    fun anEmptySlotMovesOnlyTheDroppedItem() {
        val model = LauncherModel(
            items = listOf(app("w", ContainerType.WORKSPACE, x = 1), app("d", ContainerType.DOCK, rank = 0))
        )
        val moved = LauncherRepository.movedToDock(model, "w", 3)
        assertEquals(3, moved.item("w").rank)
        assertEquals(model.item("d"), moved.item("d"))
    }

    @Test
    fun widgetsStayOffTheDock() {
        val model = LauncherModel(
            items = listOf(LauncherItem(id = "x", itemType = ItemType.WIDGET, appWidgetId = 5, spanX = 2, spanY = 2))
        )
        assertSame(model, LauncherRepository.movedToDock(model, "x", 0))
    }
}
