package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// A home folder moved into the drawer: its apps become a group's members
// under a free name, a tab can hold the new folder, and what a group can't
// hold stays on the home screen.
class FolderToDrawerTest {

    private val folder = LauncherItem(
        id = "f", itemType = ItemType.FOLDER, page = 1, cellX = 2, cellY = 3, label = "Games",
        customIconPack = "pack", customIconName = "games", iconRaw = true
    )

    private fun app(id: String, rank: Int, serial: Long = 0L) = LauncherItem(
        id = id, itemType = ItemType.APP, container = ContainerType.FOLDER, folderId = "f", rank = rank,
        packageName = "p.$id", className = "c.$id", userSerial = serial
    )

    private fun shortcut(id: String, rank: Int) = LauncherItem(
        id = id, itemType = ItemType.SHORTCUT, container = ContainerType.FOLDER, folderId = "f", rank = rank,
        packageName = "p.$id", shortcutId = "s.$id"
    )

    private val tab = DrawerGroup("Fun", setOf("0/x/y"), type = DrawerGroupType.TAB)

    @Test
    fun nameIsTheFoldersOrTheNextFreeOne() {
        assertEquals("Games", FolderToDrawer.uniqueName(" Games ", listOf("Work")))
        assertEquals("Games 3", FolderToDrawer.uniqueName("Games", listOf("Games", "Games 2")))
        assertEquals("Folder", FolderToDrawer.uniqueName("  ", emptyList()))
        assertEquals("a/b", FolderToDrawer.uniqueName("a|b", emptyList()))
    }

    @Test
    fun aDrawerFolderInATabKeepsItsAppsAndIcon() {
        val members = listOf(app("a", 0), shortcut("s", 1), app("w", 2, serial = 10L))
        val groups = FolderToDrawer.withGroupAdded(listOf(tab), folder, members, "Games", DrawerTarget.InTab("Fun"))
        val added = groups.single { it.name == "Games" }
        assertEquals(setOf("0/p.a/c.a", "10/p.w/c.w"), added.members)
        assertEquals(DrawerGroupType.FOLDER, added.type)
        assertEquals(false, added.keepInMain)
        assertEquals("pack", added.iconPack)
        assertEquals("games", added.iconName)
        assertTrue(added.iconRaw)
        assertTrue(TabFolders.keyOf("Games") in groups.single { it.name == "Fun" }.members)
    }

    @Test
    fun aTabHasNoIconAndNoTabHoldsIt() {
        val members = listOf(app("a", 0))
        val groups = FolderToDrawer.withGroupAdded(listOf(tab), folder, members, "Games", DrawerTarget.OwnTab)
        val added = groups.single { it.name == "Games" }
        assertEquals(DrawerGroupType.TAB, added.type)
        assertNull(added.iconPack)
        assertFalse(added.iconRaw)
        assertEquals(tab, groups.single { it.name == "Fun" })
    }

    @Test
    fun aFolderOfAppsLeavesHomeWhole() {
        val model = LauncherModel(items = listOf(folder, app("a", 0), app("b", 1)), pageCount = 2)
        assertTrue(FolderToDrawer.homeAfterMove(model, "f").items.isEmpty())
    }

    @Test
    fun shortcutsStayInTheFolder() {
        val model = LauncherModel(
            items = listOf(folder, app("a", 0), shortcut("s", 1), shortcut("t", 2)), pageCount = 2
        )
        val after = FolderToDrawer.homeAfterMove(model, "f")
        assertEquals(listOf("f", "s", "t"), after.items.map { it.id })
    }

    @Test
    fun aLoneShortcutTakesTheFoldersPlace() {
        val model = LauncherModel(items = listOf(folder, app("a", 0), shortcut("s", 1)), pageCount = 2)
        val left = FolderToDrawer.homeAfterMove(model, "f").items.single()
        assertEquals("s", left.id)
        assertEquals(ContainerType.WORKSPACE, left.container)
        assertEquals(1 to (2 to 3), left.page to (left.cellX to left.cellY))
        assertNull(left.folderId)
    }

    @Test
    fun aFolderOfShortcutsOnlyStays() {
        val model = LauncherModel(items = listOf(folder, shortcut("s", 0), shortcut("t", 1)), pageCount = 2)
        assertSame(model, FolderToDrawer.homeAfterMove(model, "f"))
    }
}
