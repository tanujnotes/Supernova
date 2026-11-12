package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun keyNamesItsFolderBackWhateverTheName() {
        listOf("Google", "Maps, travel", "100%", "%2C", "a%25,b", "", "folder:x").forEach { name ->
            assertEquals(name, TabFolders.nameOf(TabFolders.keyOf(name)))
        }
    }

    @Test
    fun appKeyIsNoFolder() {
        assertNull(TabFolders.nameOf(app))
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
    fun renameMovesTheKeyInEveryTab() {
        val groups = listOf(
            tab("Social", app, TabFolders.keyOf("Tools")),
            tab("Work", TabFolders.keyOf("Tools")),
            tab("Games", app),
            folder("Tools", app)
        )
        val renamed = TabFolders.renamed(groups, "Tools", "Utilities, misc")
        val key = TabFolders.keyOf("Utilities, misc")
        assertEquals(setOf(app, key), renamed[0].members)
        assertEquals(setOf(key), renamed[1].members)
        assertEquals(groups[2], renamed[2])
        assertEquals(groups[3], renamed[3])
    }

    @Test
    fun deleteDropsTheKeyFromEveryTab() {
        val groups = listOf(tab("Social", app, TabFolders.keyOf("Tools")), tab("Games", app))
        val left = TabFolders.forgotten(groups, "Tools")
        assertEquals(setOf(app), left[0].members)
        assertEquals(groups[1], left[1])
    }
}
