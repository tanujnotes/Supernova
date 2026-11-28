package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Home and dock folders inside folders (#369).
class NestedFoldersTest {

    private fun app(id: String, folder: String? = null, rank: Int = 0, page: Int = 0) = LauncherItem(
        id = id, itemType = ItemType.APP,
        container = if (folder != null) ContainerType.FOLDER else ContainerType.WORKSPACE,
        folderId = folder, rank = rank, page = page, packageName = "p.$id", className = "c.$id"
    )

    private fun folder(
        id: String,
        folder: String? = null,
        rank: Int = 0,
        container: ContainerType = if (folder != null) ContainerType.FOLDER else ContainerType.WORKSPACE,
        x: Int = 0
    ) = LauncherItem(
        id = id, itemType = ItemType.FOLDER, container = container, folderId = folder, rank = rank,
        cellX = x, label = id
    )

    private fun LauncherModel.item(id: String) = items.first { it.id == id }
    private fun LauncherModel.has(id: String) = items.any { it.id == id }

    // Outer folder O on the page holds app a and folder I; I holds b and c.
    private val nested = LauncherModel(
        items = listOf(
            folder("O", x = 2),
            app("a", "O", rank = 0),
            folder("I", "O", rank = 1),
            app("b", "I", rank = 0),
            app("c", "I", rank = 1)
        )
    )

    @Test
    fun contentsReachEveryLevel() {
        assertEquals(listOf("a", "I", "b", "c"), NestedFolders.contents(nested.items, "O").map { it.id })
        assertEquals(listOf("a", "b", "c"), NestedFolders.leaves(nested.items, "O").map { it.id })
        assertEquals(listOf("a", "I"), NestedFolders.members(nested.items, "O").map { it.id })
    }

    @Test
    fun holdersAreInnermostFirst() {
        assertEquals(listOf("I", "O"), NestedFolders.holders(nested.items, "b").map { it.id })
        assertTrue(NestedFolders.holders(nested.items, "O").isEmpty())
    }

    @Test
    fun aFolderNeverGoesIntoItselfOrOneItHolds() {
        val items = nested.items + folder("X", x = 4)
        assertFalse(NestedFolders.canHold(items, "O", items.first { it.id == "O" }))
        assertFalse(NestedFolders.canHold(items, "I", items.first { it.id == "O" }))
        assertTrue(NestedFolders.canHold(items, "X", items.first { it.id == "O" }))
        assertTrue(NestedFolders.canHold(items, "O", items.first { it.id == "X" }))
        assertTrue(NestedFolders.canHold(items, "I", items.first { it.id == "X" }))
    }

    @Test
    fun aLoopInADamagedModelEnds() {
        val items = listOf(folder("A", "B"), folder("B", "A"), app("z", "A"))
        assertEquals(setOf("B", "z"), NestedFolders.contents(items, "A").map { it.id }.toSet())
        assertEquals(listOf("A", "B"), NestedFolders.holders(items, "z").map { it.id })
    }

    @Test
    fun aFolderInsideLeftWithOneAppGivesWayInsideItsHolder() {
        val model = nested.copy(items = nested.items.filterNot { it.id == "c" })
        val next = LauncherRepository.withSparseFolderDissolved(model, "I")
        assertFalse(next.has("I"))
        assertEquals(ContainerType.FOLDER, next.item("b").container)
        assertEquals("O", next.item("b").folderId)
        assertEquals(1, next.item("b").rank)
    }

    @Test
    fun anEmptiedFolderInsideTakesItsHolderDownToOne() {
        val model = nested.copy(items = nested.items.filterNot { it.id == "b" || it.id == "c" })
        val next = LauncherRepository.withSparseFolderDissolved(model, "I")
        assertFalse(next.has("I"))
        assertFalse(next.has("O"))
        assertEquals(ContainerType.WORKSPACE, next.item("a").container)
        assertNull(next.item("a").folderId)
        assertEquals(2, next.item("a").cellX)
    }

    @Test
    fun aFolderLeftHoldingOnlyAFolderGivesWayToIt() {
        val model = nested.copy(items = nested.items.filterNot { it.id == "a" })
        val next = LauncherRepository.withSparseFolderDissolved(model, "O")
        assertFalse(next.has("O"))
        assertEquals(ContainerType.WORKSPACE, next.item("I").container)
        assertNull(next.item("I").folderId)
        assertEquals(2, next.item("I").cellX)
        assertEquals("I", next.item("b").folderId)
    }

    @Test
    fun removingAFolderTakesEverythingInsideIt() {
        val next = ItemRemoval.removed(nested, "O")
        assertTrue(next.items.isEmpty())
    }

    @Test
    fun removingAFolderInsideLeavesItsHolderWithTheRest() {
        val withMore = nested.copy(items = nested.items + app("d", "O", rank = 2))
        val next = ItemRemoval.removed(withMore, "I")
        assertEquals(setOf("O", "a", "d"), next.items.map { it.id }.toSet())
    }

    @Test
    fun removingAPageTakesItsNestedFolders() {
        val model = nested.copy(items = nested.items + app("keep", page = 1), pageCount = 2)
        val (left, removed) = PageRemoval.removed(model, 0)!!
        assertEquals(listOf("keep"), left.items.map { it.id })
        assertEquals(5, removed.items.size)
        val back = PageRemoval.restored(left, removed)
        assertEquals("I", back.item("b").folderId)
        assertEquals("O", back.item("I").folderId)
    }

    @Test
    fun uninstallingAFoldersAppsInsideLetsBothFoldersGiveWay() {
        val model = nested.copy(
            items = nested.items.map { if (it.id == "c") it.copy(packageName = "p.b") else it }
        )
        val next = PackageRemoval.applied(model, "p.b", 0L, 0L)
        assertEquals(listOf("a"), next.items.map { it.id })
        assertEquals(ContainerType.WORKSPACE, next.item("a").container)
    }

    @Test
    fun emptyFoldersGoLevelByLevel() {
        val items = listOf(folder("O"), folder("I", "O"), app("x"))
        assertEquals(listOf("x"), NestedFolders.withEmptyFoldersRemoved(items).map { it.id })
    }

    @Test
    fun aDockFolderTakesAFolderDroppedOnIt() {
        val model = LauncherModel(
            items = listOf(
                folder("D", container = ContainerType.DOCK, rank = 1),
                app("d1", "D"),
                app("d2", "D", rank = 1)
            ) + nested.items
        )
        assertEquals("D", DockFolders.target(model, model.item("O"), 1)?.id)
        val merged = DockFolders.merged(model, model.item("O"), 1, "unused")
        assertEquals("D", merged.item("O").folderId)
        assertEquals(ContainerType.FOLDER, merged.item("O").container)
        assertEquals(2, merged.item("O").rank)
        assertEquals("O", merged.item("I").folderId)
    }

    @Test
    fun aFolderOnADockAppMakesNoFolder() {
        val dockApp = app("d").copy(container = ContainerType.DOCK, rank = 0)
        val model = LauncherModel(items = listOf(dockApp) + nested.items)
        assertNull(DockFolders.target(model, model.item("O"), 0))
    }

    @Test
    fun aDockFolderNeverGoesIntoAFolderItHolds() {
        val model = LauncherModel(
            items = listOf(
                folder("D", container = ContainerType.DOCK, rank = 0),
                app("x", "D"),
                folder("I", "D", rank = 1),
                app("b", "I"),
                app("c", "I", rank = 1)
            )
        )
        // b, dragged out of I onto D's slot, would trade D into I.
        assertSame(model, LauncherRepository.movedToDock(model, "b", 0))
    }

    @Test
    fun aDockTradeCanPutAFolderInAFolder() {
        val model = LauncherModel(
            items = listOf(
                folder("D", container = ContainerType.DOCK, rank = 0),
                app("d1", "D"),
                app("d2", "D", rank = 1)
            ) + nested.items
        )
        val next = LauncherRepository.movedToDock(model, "b", 0)
        assertEquals(ContainerType.DOCK, next.item("b").container)
        assertEquals("I", next.item("D").folderId)
        assertEquals("D", next.item("d1").folderId)
    }
}
