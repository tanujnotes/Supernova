package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// A folder's Sort ranks its apps A-Z and leaves everything else alone.
class FolderSortTest {

    private fun member(id: String, label: String, rank: Int, folder: String = "f") = LauncherItem(
        id = id, itemType = ItemType.APP, container = ContainerType.FOLDER, rank = rank,
        label = label, folderId = folder, packageName = "p.$id", className = "c.$id"
    )

    private val folder = LauncherItem(id = "f", itemType = ItemType.FOLDER, label = "F")

    private fun LauncherModel.ranks(folderId: String) =
        items.filter { it.folderId == folderId }.sortedBy { it.rank }.map { it.label }

    @Test
    fun membersAreRankedByLabelIgnoringCase() {
        val model = LauncherModel(
            items = listOf(
                folder,
                member("a", "photos", 0),
                member("b", "Brave", 1),
                member("c", "Gmail", 2)
            )
        )
        val sorted = FolderSort.sorted(model, "f")
        assertEquals(listOf("Brave", "Gmail", "photos"), sorted.ranks("f"))
        assertEquals(listOf(0, 1, 2), sorted.items.filter { it.folderId == "f" }.map { it.rank }.sorted())
    }

    @Test
    fun appsWithTheSameLabelKeepTheirOrder() {
        val model = LauncherModel(
            items = listOf(
                folder,
                member("second", "Chrome", 5),
                member("first", "chrome", 2),
                member("z", "Alarm", 9)
            )
        )
        val sorted = FolderSort.sorted(model, "f")
        val order = sorted.items.filter { it.folderId == "f" }.sortedBy { it.rank }.map { it.id }
        assertEquals(listOf("z", "first", "second"), order)
    }

    @Test
    fun otherFoldersAndItemsAreUntouched() {
        val other = member("o", "Aaa", 3, folder = "g")
        val page = LauncherItem(id = "w", itemType = ItemType.APP, rank = 7, label = "Zed")
        val model = LauncherModel(
            items = listOf(folder, member("a", "Zoo", 0), member("b", "Ant", 1), other, page)
        )
        val sorted = FolderSort.sorted(model, "f")
        assertSame(other, sorted.items.first { it.id == "o" })
        assertSame(page, sorted.items.first { it.id == "w" })
        // The list order, the pages' stacking, stays as it was.
        assertEquals(model.items.map { it.id }, sorted.items.map { it.id })
    }

    @Test
    fun anAlreadySortedFolderIsTheSameModel() {
        val model = LauncherModel(
            items = listOf(folder, member("a", "Ant", 0), member("b", "Bee", 1))
        )
        assertSame(model, FolderSort.sorted(model, "f"))
    }
}
