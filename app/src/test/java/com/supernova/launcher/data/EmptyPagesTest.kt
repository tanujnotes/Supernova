package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// Remove empty pages: every page with nothing on it goes, but the home page,
// the page in view and always one page; the pages after move down.
class EmptyPagesTest {

    private fun app(id: String, page: Int, container: ContainerType = ContainerType.WORKSPACE) =
        LauncherItem(
            id = id, itemType = ItemType.APP, container = container, page = page,
            packageName = "p.$id", className = "c.$id"
        )

    private fun LauncherModel.pageOf(id: String) = items.first { it.id == id }.page

    @Test
    fun emptyPagesGoAndTheRestMoveDown() {
        val model = LauncherModel(
            items = listOf(app("a", 0), app("b", 2), app("c", 4)),
            pageCount = 5
        )
        val removed = LauncherRepository.emptyPagesToRemove(model, keep = emptySet())
        assertEquals(listOf(1, 3), removed)
        val after = LauncherRepository.withoutPages(model, removed)
        assertEquals(3, after.pageCount)
        assertEquals(listOf(0, 1, 2), listOf("a", "b", "c").map { after.pageOf(it) })
    }

    @Test
    fun theHomePageAndThePageInViewStay() {
        val model = LauncherModel(
            items = listOf(app("a", 0)),
            pageCount = 4,
            defaultPage = 2
        )
        val removed = LauncherRepository.emptyPagesToRemove(model, keep = setOf(3))
        assertEquals(listOf(1), removed)
        val after = LauncherRepository.withoutPages(model, removed)
        assertEquals(3, after.pageCount)
        assertEquals(1, after.defaultPage)
    }

    @Test
    fun oneEmptyPageAlwaysStays() {
        val model = LauncherModel(pageCount = 3, defaultPage = 5)
        assertEquals(listOf(1, 2), LauncherRepository.emptyPagesToRemove(model, keep = emptySet()))
        val after = LauncherRepository.withoutPages(model, listOf(1, 2))
        assertEquals(1, after.pageCount)
        assertEquals(0, after.defaultPage)
    }

    @Test
    fun dockAndFolderItemsDontHoldAPage() {
        val model = LauncherModel(
            items = listOf(app("a", 0), app("d", 1, ContainerType.DOCK), app("f", 2, ContainerType.FOLDER)),
            pageCount = 3
        )
        val removed = LauncherRepository.emptyPagesToRemove(model, keep = emptySet())
        assertEquals(listOf(1, 2), removed)
        val after = LauncherRepository.withoutPages(model, removed)
        // Only the workspace's pages are renumbered.
        assertEquals(1, after.pageOf("d"))
        assertEquals(2, after.pageOf("f"))
    }

    @Test
    fun anItemPastThePageCountCountsItsPage() {
        val model = LauncherModel(items = listOf(app("a", 0), app("b", 3)), pageCount = 2)
        val removed = LauncherRepository.emptyPagesToRemove(model, keep = emptySet())
        assertEquals(listOf(1, 2), removed)
        val after = LauncherRepository.withoutPages(model, removed)
        assertEquals(2, after.pageCount)
        assertEquals(1, after.pageOf("b"))
    }

    @Test
    fun nothingToRemoveLeavesTheModel() {
        val model = LauncherModel(items = listOf(app("a", 0), app("b", 1)), pageCount = 2)
        val removed = LauncherRepository.emptyPagesToRemove(model, emptySet())
        assertSame(model, LauncherRepository.withoutPages(model, removed))
    }
}
