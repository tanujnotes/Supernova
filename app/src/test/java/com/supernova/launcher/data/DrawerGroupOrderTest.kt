package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// The Drawer groups page: a drag reorders one type's groups, and Undo puts a
// deleted group back.
class DrawerGroupOrderTest {

    private val games = DrawerGroup("Games", setOf("0/a/A"), order = 0)
    private val social = DrawerGroup("Social", setOf("0/b/B"), order = 1, type = DrawerGroupType.FOLDER)
    private val travel = DrawerGroup("Travel", setOf("0/c/C"), order = 2)
    private val tools = DrawerGroup("Tools", setOf("0/d/D"), order = 3, type = DrawerGroupType.FOLDER)
    private val money = DrawerGroup("Money", setOf("0/e/E"), order = 4)
    private val all = listOf(games, social, travel, tools, money)

    private fun names(groups: List<DrawerGroup>, type: DrawerGroupType) =
        DrawerGroupOrder.sorted(groups).filter { it.type == type }.map { it.name }

    @Test
    fun aDragReordersTheTabsAndLeavesTheFoldersInPlace() {
        val moved = DrawerGroupOrder.reordered(all, DrawerGroupType.TAB, listOf("Travel", "Money", "Games"))
        assertEquals(listOf("Travel", "Money", "Games"), names(moved, DrawerGroupType.TAB))
        assertEquals(listOf("Social", "Tools"), names(moved, DrawerGroupType.FOLDER))
        assertEquals(listOf(0, 1, 2, 3, 4), DrawerGroupOrder.sorted(moved).map { it.order })
        assertEquals(setOf("0/c/C"), moved.single { it.name == "Travel" }.members)
    }

    @Test
    fun foldersReorderAmongThemselves() {
        val moved = DrawerGroupOrder.reordered(all, DrawerGroupType.FOLDER, listOf("Tools", "Social"))
        assertEquals(listOf("Tools", "Social"), names(moved, DrawerGroupType.FOLDER))
        assertEquals(listOf("Games", "Travel", "Money"), names(moved, DrawerGroupType.TAB))
    }

    @Test
    fun aGroupTheDragNeverSawFollowsTheNamedOnes() {
        val moved = DrawerGroupOrder.reordered(all, DrawerGroupType.TAB, listOf("Money", "Games", "Gone"))
        assertEquals(listOf("Money", "Games", "Travel"), names(moved, DrawerGroupType.TAB))
    }

    @Test
    fun groupsThatNeverHadAnOrderGetOne() {
        val unordered = listOf(DrawerGroup("B", emptySet()), DrawerGroup("A", emptySet()))
        val moved = DrawerGroupOrder.reordered(unordered, DrawerGroupType.TAB, listOf("B", "A"))
        assertEquals(listOf("B", "A"), names(moved, DrawerGroupType.TAB))
        assertEquals(listOf(0, 1), DrawerGroupOrder.sorted(moved).map { it.order })
    }

    @Test
    fun undoPutsADeletedGroupBackAsTheUsers() {
        val smart = tools.copy(smart = true)
        val before = listOf(games, smart)
        val restored = DrawerGroupOrder.restored(listOf(games), before, "Tools")
        val back = restored.single { it.name == "Tools" }
        assertEquals(smart.members, back.members)
        assertEquals(smart.order, back.order)
        assertFalse(back.smart)
    }

    @Test
    fun undoGivesAFolderItsPlaceInItsTabsBack() {
        val key = TabFolders.keyOf("Social")
        val holder = games.copy(members = games.members + key)
        val before = listOf(holder, social, travel)
        // The delete took the folder and its key; a group made since stays.
        val now = listOf(games, travel, money)
        val restored = DrawerGroupOrder.restored(now, before, "Social")
        assertEquals(games.members + key, restored.single { it.name == "Games" }.members)
        assertEquals(travel, restored.single { it.name == "Travel" })
        assertEquals(money, restored.single { it.name == "Money" })
        assertEquals(4, restored.size)
    }

    @Test
    fun undoChangesNothingWhenTheNameIsTakenOrUnknown() {
        val now = listOf(games, social)
        assertEquals(now, DrawerGroupOrder.restored(now, all, "Social"))
        assertEquals(now, DrawerGroupOrder.restored(now, all, "Nope"))
    }
}
