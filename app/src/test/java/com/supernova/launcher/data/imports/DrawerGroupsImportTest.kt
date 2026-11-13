package com.supernova.launcher.data.imports

import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.TabFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Another launcher's app drawer tabs and folders: read from each known
// shape of backup tables, then turned into Supernova's drawer groups.
class DrawerGroupsImportTest {

    private fun row(vararg values: Pair<String, Any?>) = Row(mapOf(*values))

    private fun groups(tables: Map<String, List<Row>>) = DrawerGroupTables.read(BackupContents(tables = tables))

    // --- Reading

    @Test
    fun appKeyReadsComponentAndUser() {
        assertEquals(ForeignApp("com.a", "com.a.Main", 0), DrawerGroupTables.app("com.a/.Main#-1"))
        assertEquals(ForeignApp("com.a", "com.a.Main", 10), DrawerGroupTables.app("com.a/com.a.Main#10"))
        assertEquals(ForeignApp("com.a", "com.a.Main", 0), DrawerGroupTables.app("com.a/com.a.Main"))
        assertNull(DrawerGroupTables.app("no-component#0"))
        assertNull(DrawerGroupTables.app(null))
    }

    @Test
    fun groupsTableReadsTabsAndFoldersAndSkipsBuiltInTabs() {
        val read = groups(
            mapOf(
                "drawer_groups" to listOf(
                    row("_id" to 1L, "title" to "Apps", "groupType" to "TAB_APPS", "tabOrder" to 0L),
                    row("_id" to 2L, "title" to "Social", "groupType" to "FOLDER_APP_GROUP", "tabOrder" to 1L,
                        "hideApps" to 1L, "icon" to byteArrayOf(1, 2, 3)),
                    row("_id" to 3L, "title" to "Work", "groupType" to "TAB_APP_GROUP", "tabOrder" to 2L,
                        "hideApps" to 0L),
                    row("_id" to 4L, "title" to "Office", "groupType" to "TAB_WORK_APPS", "tabOrder" to 3L)
                ),
                "appgroups" to listOf(
                    row("groupId" to 2L, "component" to "com.chat/.Main#-1"),
                    // Apps a smart folder already sorted: no group's.
                    row("groupId" to -100L, "component" to "com.chat/.Main#-1"),
                    row("groupId" to 3L, "component" to "com.mail/com.mail.Inbox#-1"),
                    row("groupId" to 3L, "component" to "com.launcher/FOLDER:2#-1")
                )
            )
        )
        assertEquals(listOf(2L, 3L), read.map { it.id })
        val folder = read[0]
        assertTrue(folder.folder)
        assertEquals("Social", folder.title)
        assertEquals(false, folder.keepInMain)
        assertEquals(listOf(ForeignApp("com.chat", "com.chat.Main", 0)), folder.apps)
        assertEquals(3, folder.icon?.size)
        val tab = read[1]
        assertFalse(tab.folder)
        assertEquals(true, tab.keepInMain)
        assertEquals(listOf(ForeignApp("com.mail", "com.mail.Inbox", 0)), tab.apps)
        assertEquals(listOf(2L), tab.folders)
    }

    @Test
    fun drawerFoldersReadInTheirOrder() {
        val read = groups(
            mapOf(
                "folders" to listOf(
                    row("id" to 7L, "title" to "Tools", "hide" to 0L, "rank" to 1L),
                    row("id" to 8L, "title" to "Hidden", "hide" to 1L, "rank" to 0L)
                ),
                "folderitems" to listOf(
                    row("folderId" to 7L, "rank" to 1L, "item_info" to "com.b/com.b.B#0"),
                    row("folderId" to 7L, "rank" to 0L, "item_info" to "com.a/com.a.A#0")
                )
            )
        )
        val tools = read.single { it.id == 7L }
        assertTrue(tools.folder)
        assertNull(tools.keepInMain)
        assertEquals(listOf("com.a", "com.b"), tools.apps.map { it.packageName })
        assertEquals(false, read.single { it.id == 8L }.keepInMain)
    }

    @Test
    fun categoriesBecomeTabsNamedForTheirId() {
        val read = groups(
            mapOf(
                "category" to listOf(
                    row("id" to "phone", "customLabel" to null, "enabled" to 1L, "position" to 0L),
                    row("id" to "utility", "customLabel" to null, "enabled" to 1L, "position" to 1L),
                    row("id" to "c42", "customLabel" to "Reading", "enabled" to 1L, "position" to 2L),
                    // One of its own with nothing in it.
                    row("id" to "media", "customLabel" to null, "enabled" to 1L, "position" to 4L),
                    row("id" to "games", "customLabel" to null, "enabled" to 0L, "position" to 3L)
                ),
                "draweritem" to listOf(
                    row("categoryId" to "phone", "packageName" to "com.dialer", "activityName" to "com.dialer.D",
                        "userId" to 0L),
                    row("categoryId" to "utility", "packageName" to "com.notes", "activityName" to "com.notes.N",
                        "userId" to 0L),
                    // An app's shortcut is no tab member.
                    row("categoryId" to "utility", "packageName" to "com.notes", "deepShortcutId" to "new",
                        "parentId" to 2L)
                )
            )
        )
        assertEquals(listOf("Contacts", "Utilities", "Reading"), read.map { it.title })
        assertTrue(read.none { it.folder })
        assertTrue(read.all { it.keepInMain == true })
        assertEquals(listOf(ForeignApp("com.notes", "com.notes.N", 0)), read[1].apps)
    }

    @Test
    fun layoutCarriesItsDrawerGroups() {
        val layout = BackupLayouts.layout(
            BackupContents(
                tables = mapOf(
                    "favorites" to emptyList(),
                    "folders" to listOf(row("id" to 1L, "title" to "F", "rank" to 0L)),
                    "folderitems" to listOf(row("folderId" to 1L, "item_info" to "com.a/com.a.A#0"))
                )
            )
        )
        assertEquals(listOf("F"), layout?.groups?.map { it.title })
    }

    // --- Converting

    private val phone = LayoutConverter.Phone(
        encodeIcon = { bytes -> "icon${bytes.size}" },
        installed = { pkg, _ -> pkg != "com.gone" }
    )

    private fun group(id: Long, title: String, folder: Boolean, vararg packages: String, order: Int = 0) =
        ForeignGroup(id, title, folder, order, apps = packages.map { ForeignApp(it, "$it.Main") })

    @Test
    fun groupsBecomeDrawerGroupsWithMemberKeys() {
        val made = DrawerGroupConverter.convert(
            listOf(
                group(5, "Tab B", folder = false, "com.b", order = 2),
                group(4, "Tab A", folder = false, "com.a", order = 1)
                    .let { ForeignGroup(it.id, it.title, false, it.order, false, it.apps, folders = listOf(9)) },
                ForeignGroup(9, "Chat", true, 0, apps = listOf(ForeignApp("com.c", "com.c.Main")),
                    icon = byteArrayOf(1, 2))
            ),
            phone
        )
        val folder = made.single { it.type == DrawerGroupType.FOLDER }
        assertEquals("Chat", folder.name)
        assertEquals(setOf("0/com.c/com.c.Main"), folder.members)
        assertEquals("icon2", folder.iconBase64)
        val tabs = made.filter { it.type == DrawerGroupType.TAB }
        assertEquals(listOf("Tab A", "Tab B"), tabs.map { it.name })
        assertEquals(listOf(0, 1), tabs.map { it.order })
        assertEquals(setOf("0/com.a/com.a.Main", TabFolders.keyOf("Chat")), tabs[0].members)
        assertEquals(false, tabs[0].keepInMain)
    }

    @Test
    fun missingAppsAndEmptiedFoldersAreLeftOut() {
        val made = DrawerGroupConverter.convert(
            listOf(
                group(1, "Gone", folder = true, "com.gone"),
                group(2, "Tab", folder = false, "com.gone"),
                ForeignGroup(3, "Holds gone", false, 1, folders = listOf(1))
            ),
            phone
        )
        assertEquals(listOf("Tab", "Holds gone"), made.map { it.name })
        assertTrue(made.all { it.members.isEmpty() })
    }

    @Test
    fun namesStayUniqueAndStorable() {
        val made = DrawerGroupConverter.convert(
            listOf(
                group(1, "Fun", folder = true, "com.a"),
                group(2, "fun", folder = false),
                group(3, "  ", folder = false),
                group(4, "A|B", folder = false)
            ),
            phone
        )
        assertEquals(listOf("Fun", "fun 2", "Tab", "A/B"), made.map { it.name })
        made.forEach { assertEquals(it, DrawerGroup.decode(it.encode())) }
    }

    @Test
    fun importReplacesSameNamedGroupsAndKeepsTheRest() {
        val stored = setOf(
            DrawerGroup("Work", setOf("0/old/old.Main")).encode(),
            DrawerGroup("Mine", setOf("0/x/x.Main")).encode()
        )
        val prefs = LayoutConverter.convert(
            ForeignLayout(emptyList(), ForeignGrid(), groups = listOf(group(1, "Work", folder = false, "com.w"))),
            LauncherPrefs(drawerGroups = stored),
            phone
        ).prefs
        val byName = prefs.drawerGroups.mapNotNull(DrawerGroup::decode).associateBy { it.name }
        assertEquals(setOf("Work", "Mine"), byName.keys)
        assertEquals(setOf("0/com.w/com.w.Main"), byName.getValue("Work").members)
    }

    @Test
    fun noGroupsLeavesStoredGroupsAlone() {
        val stored = setOf(DrawerGroup("Mine", setOf("0/x/x.Main")).encode())
        val prefs = LayoutConverter.convert(
            ForeignLayout(emptyList(), ForeignGrid()), LauncherPrefs(drawerGroups = stored), phone
        ).prefs
        assertEquals(stored, prefs.drawerGroups)
    }
}
