package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

// Undo of a removal puts items back without landing one on another.
class UndoRestoreTest {

    private fun dock(id: String, rank: Int) = LauncherItem(
        id = id, itemType = ItemType.APP, container = ContainerType.DOCK, rank = rank,
        packageName = "p.$id", className = "c.$id"
    )

    private fun LauncherModel.item(id: String) = items.first { it.id == id }

    @Test
    fun aDockItemGoesBackToItsFreeSlot() {
        val model = LauncherModel(items = listOf(dock("a", 0)))
        val restored = LauncherRepository.restored(model, listOf(dock("b", 2)), 4, 5, dockSlots = 5)
        assertEquals(2, restored.item("b").rank)
        assertEquals(ContainerType.DOCK, restored.item("b").container)
    }

    @Test
    fun aDockItemWhoseSlotFilledTakesTheFirstFreeOne() {
        val model = LauncherModel(items = listOf(dock("a", 0), dock("c", 2)))
        val restored = LauncherRepository.restored(model, listOf(dock("b", 2)), 4, 5, dockSlots = 5)
        assertEquals(1, restored.item("b").rank)
        assertEquals(2, restored.item("c").rank)
    }

    @Test
    fun withTheDockFullItGoesToTheHomePage() {
        val model = LauncherModel(items = listOf(dock("a", 0), dock("c", 1)), pageCount = 2, defaultPage = 1)
        val restored = LauncherRepository.restored(model, listOf(dock("b", 1)), 4, 5, dockSlots = 2)
        val b = restored.item("b")
        assertEquals(ContainerType.WORKSPACE, b.container)
        assertEquals(1, b.page)
    }

    @Test
    fun twoRestoredDockItemsDontShareASlot() {
        val model = LauncherModel(items = listOf(dock("a", 0)))
        val restored = LauncherRepository.restored(model, listOf(dock("b", 0), dock("c", 0)), 4, 5, dockSlots = 5)
        val ranks = listOf("a", "b", "c").map { restored.item(it).rank }
        assertEquals(3, ranks.toSet().size)
    }

    @Test
    fun itemsStillThereAreLeftAlone() {
        val model = LauncherModel(items = listOf(dock("a", 0)))
        assertSame(model, LauncherRepository.restored(model, listOf(dock("a", 3)), 4, 5, dockSlots = 5))
    }
}
