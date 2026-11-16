package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// "Add new apps to Home screen": the first free cell from the first page on,
// else a new page after the last.
class AutoAddAppTest {

    private val newApp = LaunchableApp(packageName = "p.new", className = "c.new", label = "New")

    private fun app(id: String, page: Int, x: Int, y: Int) =
        LauncherItem(
            id = id, itemType = ItemType.APP, container = ContainerType.WORKSPACE, page = page,
            cellX = x, cellY = y, packageName = "p.$id", className = "c.$id"
        )

    private fun fullPage(page: Int) =
        (0 until 2).flatMap { y -> (0 until 2).map { x -> app("$page-$x-$y", page, x, y) } }

    private fun LauncherModel.added() = items.first { it.packageName == "p.new" }

    @Test
    fun firstFreeCellFromTheFirstPage() {
        val model = LauncherModel(items = fullPage(0) + app("b", 1, 0, 0), pageCount = 3, defaultPage = 2)
        val added = AutoAdd.place(model, newApp, "n", 2, 2).added()
        assertEquals(listOf(1, 1, 0), listOf(added.page, added.cellX, added.cellY))
    }

    @Test
    fun everyPageFullAddsANewLastPage() {
        val model = LauncherModel(items = fullPage(0) + fullPage(1), pageCount = 2)
        val after = AutoAdd.place(model, newApp, "n", 2, 2)
        assertEquals(listOf(2, 0, 0), listOf(after.added().page, after.added().cellX, after.added().cellY))
        assertEquals(3, after.pageCount)
    }

    @Test
    fun noRoomForAnotherPageLeavesItOut() {
        val model = LauncherModel(
            items = (0 until LauncherRepository.MAX_PAGES).flatMap { fullPage(it) },
            pageCount = LauncherRepository.MAX_PAGES
        )
        assertSame(model, AutoAdd.place(model, newApp, "n", 2, 2))
    }

    @Test
    fun alreadyOnHomeIsLeftAlone() {
        val model = LauncherModel(items = listOf(app("x", 0, 0, 0).copy(packageName = "p.new")))
        assertSame(model, AutoAdd.place(model, newApp, "n", 2, 2))
    }
}
