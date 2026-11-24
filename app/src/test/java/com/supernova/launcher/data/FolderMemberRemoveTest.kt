package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Removing an app from a two-app folder leaves the other app in the folder's
// place, and Undo brings the folder back as it was (#345).
class FolderMemberRemoveTest {

    private fun member(id: String, rank: Int, folder: String = "f") = LauncherItem(
        id = id, itemType = ItemType.APP, container = ContainerType.FOLDER, rank = rank,
        folderId = folder, packageName = "p.$id", className = "c.$id"
    )

    private val folder = LauncherItem(
        id = "f", itemType = ItemType.FOLDER, page = 0, cellX = 2, cellY = 3,
        label = "Games", customIconPack = "pack", customIconName = "games"
    )

    private val other = LauncherItem(
        id = "w", itemType = ItemType.APP, page = 0, cellX = 0, cellY = 0,
        packageName = "p.w", className = "c.w"
    )

    private fun LauncherModel.item(id: String) = items.first { it.id == id }

    @Test
    fun removingOneOfTwoLeavesTheOtherInTheFoldersPlace() {
        val model = LauncherModel(items = listOf(other, folder, member("a", 0), member("b", 1)))
        val after = ItemRemoval.removed(model, "a")
        assertTrue(after.items.none { it.id == "f" || it.id == "a" })
        val b = after.item("b")
        assertEquals(ContainerType.WORKSPACE, b.container)
        assertNull(b.folderId)
        assertEquals(2, b.cellX)
        assertEquals(3, b.cellY)
    }

    @Test
    fun removingOneOfThreeKeepsTheFolder() {
        val model = LauncherModel(items = listOf(folder, member("a", 0), member("b", 1), member("c", 2)))
        val after = ItemRemoval.removed(model, "a")
        assertEquals(ItemType.FOLDER, after.item("f").itemType)
        assertEquals(listOf("b", "c"), after.items.filter { it.folderId == "f" }.map { it.id })
    }

    @Test
    fun undoBringsTheFolderBackWithBothApps() {
        val captured = listOf(folder, member("a", 0), member("b", 1))
        val model = LauncherModel(items = listOf(other) + captured)
        val removed = ItemRemoval.removed(model, "a")
        val undone = ItemRemoval.replaced(removed, captured, 5, 6, dockSlots = 5)
        val f = undone.item("f")
        assertEquals(ItemType.FOLDER, f.itemType)
        assertEquals("Games", f.label)
        assertEquals("games", f.customIconName)
        assertEquals(2, f.cellX)
        assertEquals(3, f.cellY)
        assertEquals(listOf("a", "b"), undone.items.filter { it.folderId == "f" }.sortedBy { it.rank }.map { it.id })
        assertTrue(undone.items.filter { it.folderId == "f" }.all { it.container == ContainerType.FOLDER })
        assertEquals(4, undone.items.size)
    }
}
