package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// #215: a page goes with everything on it, and Undo puts it back as it was.
class PageRemovalTest {

    private fun item(
        id: String,
        page: Int,
        type: ItemType = ItemType.APP,
        container: ContainerType = ContainerType.WORKSPACE,
        folderId: String? = null
    ) = LauncherItem(
        id = id, itemType = type, container = container, page = page, folderId = folderId,
        packageName = "p.$id", className = "c.$id"
    )

    private val model = LauncherModel(
        items = listOf(
            item("a", 0),
            item("b", 1),
            item("f", 1, ItemType.FOLDER),
            item("m", 0, container = ContainerType.FOLDER, folderId = "f"),
            item("w", 1, ItemType.WIDGET),
            item("c", 2),
            item("d", 0, container = ContainerType.DOCK)
        ),
        pageCount = 3,
        defaultPage = 2
    )

    private fun LauncherModel.pageOf(id: String) = items.first { it.id == id }.page

    @Test
    fun thePageGoesWithItsItemsAndFolderMembers() {
        val (after, removed) = PageRemoval.removed(model, 1)!!
        assertEquals(2, after.pageCount)
        assertEquals(listOf("a", "c", "d"), after.items.map { it.id })
        assertEquals(1, after.pageOf("c"))
        assertEquals(1, after.defaultPage)
        assertEquals(setOf("b", "f", "m", "w"), removed.items.map { it.id }.toSet())
        assertEquals(listOf("w"), removed.widgets.map { it.id })
    }

    @Test
    fun undoPutsThePageBackAsItWas() {
        val (after, removed) = PageRemoval.removed(model, 1)!!
        val back = PageRemoval.restored(after, removed)
        assertEquals(model.pageCount, back.pageCount)
        assertEquals(model.defaultPage, back.defaultPage)
        assertEquals(model.items.sortedBy { it.id }, back.items.sortedBy { it.id })
    }

    @Test
    fun theHomePageComesBackAsHome() {
        val (after, removed) = PageRemoval.removed(model, 2)!!
        assertEquals(1, after.defaultPage)
        val back = PageRemoval.restored(after, removed)
        assertEquals(2, back.defaultPage)
        assertEquals(2, back.pageOf("c"))
    }

    @Test
    fun theOnlyPageStays() {
        assertNull(PageRemoval.removed(LauncherModel(items = listOf(item("a", 0)), pageCount = 1), 0))
        assertNull(PageRemoval.removed(model, 5))
    }

    @Test
    fun itemsBackAlreadyAreNotDoubled() {
        val (after, removed) = PageRemoval.removed(model, 0)!!
        val withA = after.copy(items = after.items + item("a", 0))
        val back = PageRemoval.restored(withA, removed)
        assertEquals(1, back.items.count { it.id == "a" })
        assertEquals(3, back.pageCount)
    }
}
