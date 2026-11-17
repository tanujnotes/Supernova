package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Drawer folders held by drawer tabs: the member key survives the group
// codec whatever the folder is called, a tab shows exactly the folders it
// holds, a tab that takes its apps out of the main list takes its folders
// too, and renames and deletes keep the tabs' keys in step.
class TabFoldersTest {

    private val app = "0/com.a/com.a.Main"

    private fun folder(name: String, vararg members: String) =
        DrawerGroup(name, members.toSet(), type = DrawerGroupType.FOLDER)

    private fun tab(name: String, vararg members: String) =
        DrawerGroup(name, members.toSet(), type = DrawerGroupType.TAB)

    @Test
    fun keyGivesItsFolderIdBackWhateverTheId() {
        listOf("Google", "Maps, travel", "100%", "%2C", "a%25,b", "", "folder:x", "#0a1b2c").forEach { id ->
            assertEquals(id, TabFolders.idOf(TabFolders.keyOf(id)))
        }
    }

    @Test
    fun appKeyIsNoFolder() {
        assertNull(TabFolders.idOf(app))
    }

    @Test
    fun keyHoldsNoMemberSeparator() {
        val held = tab("Social", app, TabFolders.keyOf("Maps, travel"), TabFolders.keyOf("Tools"))
        assertEquals(held, DrawerGroup.decode(held.encode()))
    }

    @Test
    fun tabShowsTheFoldersItHolds() {
        val google = folder("Google", app)
        val tools = folder("Tools", app)
        val social = tab("Social", app, TabFolders.keyOf("Tools"))
        assertEquals(listOf(tools), TabFolders.inTab(social, listOf(google, tools)))
        assertEquals(emptyList<DrawerGroup>(), TabFolders.inTab(tab("Empty"), listOf(google, tools)))
    }

    @Test
    fun mainListLosesOnlyFoldersAnIsolatingTabHolds() {
        val google = folder("Google", app)
        val tools = folder("Tools", app)
        val isolated = setOf(app, TabFolders.keyOf("Tools"))
        assertEquals(listOf(google), TabFolders.onMainList(listOf(google, tools), isolated))
        assertEquals(listOf(google, tools), TabFolders.onMainList(listOf(google, tools), emptySet()))
    }

    @Test
    fun aTabHoldsAFolderByIdNotName() {
        val first = folder("Tools", app)
        val second = DrawerGroup("Tools", setOf(app), type = DrawerGroupType.FOLDER, id = "#t2")
        val renamed = DrawerGroup("Utilities", setOf(app), type = DrawerGroupType.FOLDER, id = "#t3")
        val social = tab("Social", TabFolders.keyOf("#t2"), TabFolders.keyOf("#t3"))
        assertEquals(listOf(second, renamed), TabFolders.inTab(social, listOf(first, second, renamed)))
    }

    @Test
    fun deleteDropsTheKeyFromEveryTab() {
        val groups = listOf(tab("Social", app, TabFolders.keyOf("Tools")), tab("Games", app))
        val left = TabFolders.forgotten(groups, "Tools")
        assertEquals(setOf(app), left[0].members)
        assertEquals(groups[1], left[1])
    }

    // --- Folders inside folders (#229)

    @Test
    fun aFolderHoldsFoldersButNeverItself() {
        val social = folder("Social", app)
        val travel = folder("Travel", app, TabFolders.keyOf("Social"), TabFolders.keyOf("Travel"))
        assertEquals(listOf(social), TabFolders.heldBy(travel, listOf(social, travel)))
    }

    @Test
    fun noFolderGoesIntoOneItSitsIn() {
        val maps = folder("Maps", app)
        val social = folder("Social", app, TabFolders.keyOf("Maps"))
        val travel = folder("Travel", TabFolders.keyOf("Social"))
        val all = listOf(maps, social, travel)
        assertTrue(TabFolders.canHold(travel, maps, all))
        assertFalse(TabFolders.canHold(maps, travel, all))
        assertFalse(TabFolders.canHold(social, travel, all))
        assertFalse(TabFolders.canHold(travel, travel, all))
        assertEquals(setOf("Social", "Maps"), TabFolders.nestedIds(travel, all))
    }

    @Test
    fun aLoopEndsWhereItComesRound() {
        val a = folder("A", TabFolders.keyOf("B"))
        val b = folder("B", TabFolders.keyOf("A"))
        assertEquals(setOf("B"), TabFolders.nestedIds(a, listOf(a, b)))
        assertFalse(TabFolders.hasContent(a, listOf(a, b)) { it.members.contains(app) })
    }

    @Test
    fun aFolderOfFoldersShowsWhileOneHasApps() {
        val empty = folder("Empty")
        val social = folder("Social", app)
        val holder = folder("Holder", TabFolders.keyOf("Empty"))
        val all = listOf(empty, social, holder)
        val hasApps: (DrawerGroup) -> Boolean = { app in it.members }
        assertFalse(TabFolders.hasContent(holder, all, hasApps))
        val filled = holder.copy(members = holder.members + TabFolders.keyOf("Social"))
        assertTrue(TabFolders.hasContent(filled, listOf(empty, social, filled), hasApps))
    }

    @Test
    fun aHeldFolderLeavesTheMainListWithItsHolder() {
        val social = folder("Social", app)
        val travel = folder("Travel", TabFolders.keyOf("Social"))
        val all = listOf(social, travel)
        assertEquals(setOf(TabFolders.keyOf("Social")), TabFolders.nestedKeys(all) { true })
        assertEquals(emptySet<String>(), TabFolders.nestedKeys(all) { false })
    }
}
