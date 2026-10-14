package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// An app taken out of a folder can leave it with one app or none: the last
// app then takes the folder's place, and an empty folder goes.
class FolderDissolveTest {

    private fun app(id: String, container: ContainerType, page: Int = 0, x: Int = 0, y: Int = 0, rank: Int = 0, folder: String? = null) =
        LauncherItem(
            id = id, itemType = ItemType.APP, container = container, page = page, cellX = x, cellY = y,
            rank = rank, folderId = folder, packageName = "p.$id", className = "c.$id"
        )

    private fun LauncherModel.item(id: String) = items.first { it.id == id }

    private val pageFolder = LauncherItem(
        id = "f", itemType = ItemType.FOLDER, page = 1, cellX = 2, cellY = 3,
        workspaceX = 0.5f, workspaceY = 0.6f, label = "F"
    )

    @Test
    fun aFolderWithTwoAppsLeftStays() {
        val model = LauncherModel(
            items = listOf(
                pageFolder,
                app("a", ContainerType.FOLDER, rank = 0, folder = "f"),
                app("b", ContainerType.FOLDER, rank = 1, folder = "f")
            ),
            pageCount = 2
        )
        assertSame(model, LauncherRepository.withSparseFolderDissolved(model, "f"))
    }

    @Test
    fun theLastAppTakesThePageFoldersPlace() {
        val model = LauncherModel(
            items = listOf(
                app("w", ContainerType.WORKSPACE, x = 0),
                pageFolder,
                app("x", ContainerType.WORKSPACE, x = 1),
                app("b", ContainerType.FOLDER, page = 0, x = 0, y = 0, rank = 4, folder = "f")
            ),
            pageCount = 2
        )
        val dissolved = LauncherRepository.withSparseFolderDissolved(model, "f")
        assertTrue(dissolved.items.none { it.id == "f" })
        val last = dissolved.item("b")
        assertEquals(ContainerType.WORKSPACE, last.container)
        assertNull(last.folderId)
        assertEquals(1, last.page)
        assertEquals(2, last.cellX)
        assertEquals(3, last.cellY)
        assertEquals(0.5f, last.workspaceX)
        assertEquals(0.6f, last.workspaceY)
        // The folder's place in the page's stacking, too.
        assertEquals(listOf("w", "b", "x"), dissolved.items.map { it.id })
    }

    @Test
    fun theLastAppTakesTheDockedFoldersSlot() {
        val model = LauncherModel(
            items = listOf(
                LauncherItem(id = "f", itemType = ItemType.FOLDER, container = ContainerType.DOCK, rank = 3, label = "F"),
                app("b", ContainerType.FOLDER, rank = 1, folder = "f")
            )
        )
        val last = LauncherRepository.withSparseFolderDissolved(model, "f").item("b")
        assertEquals(ContainerType.DOCK, last.container)
        assertEquals(3, last.rank)
        assertNull(last.folderId)
    }

    @Test
    fun anEmptiedFolderGoes() {
        val model = LauncherModel(items = listOf(pageFolder, app("w", ContainerType.WORKSPACE)), pageCount = 2)
        assertEquals(listOf("w"), LauncherRepository.withSparseFolderDissolved(model, "f").items.map { it.id })
    }

    @Test
    fun anythingButAFolderIsLeftAlone() {
        val model = LauncherModel(items = listOf(app("w", ContainerType.WORKSPACE)))
        assertSame(model, LauncherRepository.withSparseFolderDissolved(model, "w"))
        assertSame(model, LauncherRepository.withSparseFolderDissolved(model, null))
    }

    @Test
    fun anAppOutToAnEmptyDockSlotDissolvesItsFolder() {
        val model = LauncherModel(
            items = listOf(
                pageFolder,
                app("a", ContainerType.FOLDER, rank = 0, folder = "f"),
                app("b", ContainerType.FOLDER, rank = 1, folder = "f")
            ),
            pageCount = 2
        )
        val moved = LauncherRepository.movedToDock(model, "a", 2)
        assertEquals(ContainerType.DOCK, moved.item("a").container)
        assertEquals(2, moved.item("a").rank)
        assertTrue(moved.items.none { it.id == "f" })
        val last = moved.item("b")
        assertEquals(ContainerType.WORKSPACE, last.container)
        assertEquals(2, last.cellX)
        assertEquals(3, last.cellY)
    }

    @Test
    fun aDockTradeKeepsTheFolderAsFullAsItWas() {
        // The slot's icon goes into the folder in the app's place.
        val model = LauncherModel(
            items = listOf(
                pageFolder,
                app("a", ContainerType.FOLDER, rank = 0, folder = "f"),
                app("b", ContainerType.FOLDER, rank = 1, folder = "f"),
                app("d", ContainerType.DOCK, rank = 0)
            ),
            pageCount = 2
        )
        val moved = LauncherRepository.movedToDock(model, "a", 0)
        assertEquals(model.item("f"), moved.item("f"))
        assertEquals("f", moved.item("d").folderId)
        assertEquals("f", moved.item("b").folderId)
    }
}
