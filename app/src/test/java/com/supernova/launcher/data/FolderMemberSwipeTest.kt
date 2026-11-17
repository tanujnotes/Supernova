package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

// An app's own swipes go wherever the app goes: into a folder, out of one,
// and onto the folder's place when the folder gives way to its last app (#224).
class FolderMemberSwipeTest {

    private fun app(id: String, container: ContainerType, rank: Int = 0, folder: String? = null) =
        LauncherItem(
            id = id, itemType = ItemType.APP, container = container, rank = rank, folderId = folder,
            packageName = "p.$id", className = "c.$id",
            swipeAction = "up.$id", swipeLabel = "Up $id", swipeActionDown = "down.$id", swipeLabelDown = "Down $id"
        )

    private fun LauncherModel.item(id: String) = items.first { it.id == id }

    private fun assertSwipes(item: LauncherItem) {
        assertEquals("up.${item.id}", item.swipeAction)
        assertEquals("Up ${item.id}", item.swipeLabel)
        assertEquals("down.${item.id}", item.swipeActionDown)
        assertEquals("Down ${item.id}", item.swipeLabelDown)
    }

    private val folder = LauncherItem(id = "f", itemType = ItemType.FOLDER, cellX = 1, cellY = 1, label = "F")

    @Test
    fun aDockTradeKeepsBothAppsSwipes() {
        // The member goes to the dock, the dock app into the folder.
        val model = LauncherModel(
            items = listOf(
                folder,
                app("m", ContainerType.FOLDER, folder = "f"),
                app("n", ContainerType.FOLDER, rank = 1, folder = "f"),
                app("d", ContainerType.DOCK)
            ),
            pageCount = 1
        )
        val moved = LauncherRepository.movedToDock(model, "m", 0)
        assertEquals(ContainerType.DOCK, moved.item("m").container)
        assertEquals(ContainerType.FOLDER, moved.item("d").container)
        assertSwipes(moved.item("m"))
        assertSwipes(moved.item("d"))
    }

    @Test
    fun theLastAppKeepsItsSwipesWhenTheFolderGivesWay() {
        val model = LauncherModel(items = listOf(folder, app("m", ContainerType.FOLDER, folder = "f")), pageCount = 1)
        val dissolved = LauncherRepository.withSparseFolderDissolved(model, "f")
        val last = dissolved.item("m")
        assertEquals(ContainerType.WORKSPACE, last.container)
        assertSwipes(last)
    }
}
