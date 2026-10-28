package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// A drop on the middle of a dock icon makes a folder in that slot, and a drop
// on a dock folder adds to it.
class DockFolderTest {

    private fun item(
        id: String,
        container: ContainerType,
        type: ItemType = ItemType.APP,
        rank: Int = 0,
        folder: String? = null
    ) =
        LauncherItem(
            id = id, itemType = type, container = container, rank = rank, folderId = folder,
            packageName = "p.$id", className = "c.$id"
        )

    private fun LauncherModel.item(id: String) = items.first { it.id == id }

    @Test
    fun pageAppOnDockAppMakesAFolderInTheSlot() {
        val model = LauncherModel(
            items = listOf(item("d", ContainerType.DOCK, rank = 2), item("p", ContainerType.WORKSPACE)),
            pageCount = 1
        )
        val merged = DockFolders.merged(model, model.item("p"), 2, "f")
        val folder = merged.item("f")
        assertEquals(ItemType.FOLDER, folder.itemType)
        assertEquals(ContainerType.DOCK, folder.container)
        assertEquals(2, folder.rank)
        assertEquals("f", merged.item("d").folderId)
        assertEquals(0, merged.item("d").rank)
        assertEquals("f", merged.item("p").folderId)
        assertEquals(1, merged.item("p").rank)
    }

    @Test
    fun drawerAppOnDockFolderJoinsItLast() {
        val model = LauncherModel(
            items = listOf(
                item("f", ContainerType.DOCK, ItemType.FOLDER, rank = 0),
                item("a", ContainerType.FOLDER, rank = 0, folder = "f"),
                item("b", ContainerType.FOLDER, rank = 1, folder = "f")
            ),
            pageCount = 1
        )
        val merged = DockFolders.merged(model, item("n", ContainerType.WORKSPACE), 0, "unused")
        assertEquals(ContainerType.FOLDER, merged.item("n").container)
        assertEquals("f", merged.item("n").folderId)
        assertEquals(2, merged.item("n").rank)
        assertEquals(4, merged.items.size)
    }

    @Test
    fun appLeavingATwoAppFolderDissolvesIt() {
        val model = LauncherModel(
            items = listOf(
                item("d", ContainerType.DOCK, rank = 1),
                item("g", ContainerType.WORKSPACE, ItemType.FOLDER),
                item("a", ContainerType.FOLDER, rank = 0, folder = "g"),
                item("b", ContainerType.FOLDER, rank = 1, folder = "g")
            ),
            pageCount = 1
        )
        val merged = DockFolders.merged(model, model.item("a"), 1, "f")
        assertEquals("f", merged.item("a").folderId)
        assertEquals(ContainerType.WORKSPACE, merged.item("b").container)
        assertEquals(null, merged.items.firstOrNull { it.id == "g" })
    }

    @Test
    fun aFolderGoesIntoNoFolder() {
        val model = LauncherModel(
            items = listOf(
                item("d", ContainerType.DOCK, rank = 0),
                item("g", ContainerType.WORKSPACE, ItemType.FOLDER)
            ),
            pageCount = 1
        )
        assertSame(model, DockFolders.merged(model, model.item("g"), 0, "f"))
    }
}
