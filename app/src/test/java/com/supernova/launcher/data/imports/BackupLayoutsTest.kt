package com.supernova.launcher.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

// Reading another launcher's backup: the intent text, the preferences file,
// the protocol buffers, and the two families of layout tables.
class BackupLayoutsTest {

    private fun appIntent(component: String) =
        "#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;" +
            "launchFlags=0x10200000;component=$component;end"

    private fun favorite(
        id: Long,
        type: Int,
        container: Long,
        vararg more: Pair<String, Any?>
    ) = Row(mapOf("_id" to id, "itemType" to type.toLong(), "container" to container) + more)

    // --- Intent text

    @Test
    fun intentTextReadsComponentAndExtras() {
        val intent = IntentText.parse(
            "#Intent;action=android.intent.action.MAIN;category=com.example.DEEP;package=com.maps;" +
                "component=com.maps/.Main%24Home;S.shortcut_id=work%20route;i.count=2;end"
        )!!
        assertEquals("android.intent.action.MAIN", intent.action)
        assertEquals(listOf("com.example.DEEP"), intent.categories)
        assertEquals("com.maps" to "com.maps.Main\$Home", intent.component)
        assertEquals("work route", intent.extras["shortcut_id"])
        assertEquals("2", intent.extras["count"])
        assertTrue(intent.uri.startsWith("intent:#Intent;"))
    }

    @Test
    fun intentTextRejectsWhatIsNoIntent() {
        assertNull(IntentText.parse(null))
        assertNull(IntentText.parse("https://example.com"))
        assertNull(IntentText.component("no-slash"))
        assertEquals("a.b" to "c.D", IntentText.component("a.b/c.D"))
    }

    // --- Preferences file

    @Test
    fun prefsXmlReadsEveryValueKind() {
        val prefs = PrefsXml.parse(
            """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="desktop_grid">7x4 subgrid</string>
                <int name="dock_grid_cols" value="6" />
                <boolean name="flag" value="true" />
                <string name="quoted">[&quot;A&quot;]</string>
                <set name="many"><string>one</string><string>two</string></set>
            </map>"""
        )
        assertEquals("7x4 subgrid", prefs["desktop_grid"])
        assertEquals("6", prefs["dock_grid_cols"])
        assertEquals("true", prefs["flag"])
        assertEquals("[\"A\"]", prefs["quoted"])
        assertEquals("one\ntwo", prefs["many"])
    }

    @Test
    fun prefsXmlIgnoresOtherDocuments() {
        assertTrue(PrefsXml.parse("<manifest><string name=\"a\">b</string></manifest>").isEmpty())
        val declared = "<!DOCTYPE map [<!ENTITY x \"y\">]><map><string name=\"a\">&x;</string></map>"
        assertTrue(PrefsXml.parse(declared).isEmpty())
        assertTrue(PrefsXml.parse("not xml at all").isEmpty())
    }

    // --- Protocol buffers

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var rest = value
        while (rest >= 0x80) {
            out.write(((rest and 0x7f) or 0x80).toInt())
            rest = rest ushr 7
        }
        out.write(rest.toInt())
        return out.toByteArray()
    }

    private fun number(field: Int, value: Long) = varint((field shl 3).toLong()) + varint(value)

    private fun bytes(field: Int, value: ByteArray) =
        varint(((field shl 3) or 2).toLong()) + varint(value.size.toLong()) + value

    private fun text(field: Int, value: String) = bytes(field, value.toByteArray())

    @Test
    fun protoFieldsReadNestedMessages() {
        val grid = text(1, "4,6") + number(2, 5) + number(3, 0)
        val info = number(1, 1500) + bytes(7, grid) + number(8, 1)
        val parsed = ProtoFields.parse(info)!!
        assertEquals(1500, parsed.int(1))
        assertEquals("4,6", parsed.message(7)?.text(1))
        assertEquals(5, parsed.message(7)?.int(2))
        assertNull(parsed.message(9))
    }

    @Test
    fun protoFieldsRejectTruncatedBytes() {
        assertNull(ProtoFields.parse(text(1, "hello").dropLast(2).toByteArray()))
    }

    // --- One table of placed items

    @Test
    fun pagedFamilyReadsHalfCellsDockPagesAndGrid() {
        val rows = listOf(
            // A marker row with no place: passed over, not counted.
            Row(mapOf("_id" to -1L, "itemType" to -1L, "container" to null, "zOrder" to 0L)),
            favorite(
                1, 0, -100, "screen" to 1L, "cellX" to 9.5, "cellY" to 2.5, "spanX" to 1.0, "spanY" to 1.0,
                "title" to "Mail", "intent" to appIntent("com.mail/.Main"), "profileId" to -1L, "zOrder" to 0L
            ),
            favorite(2, 0, -101, "screen" to 1L, "cellX" to 3.0, "intent" to appIntent("com.phone/.Dial")),
            favorite(3, 2, -100, "screen" to 0L, "cellX" to 0.0, "cellY" to 0.0, "title" to "Tools"),
            favorite(4, 0, 3, "rank" to 1L, "intent" to appIntent("com.calc/.Calc"), "profileId" to 10L),
            favorite(
                5, 4, -100, "cellX" to 4.0, "cellY" to 10.0, "spanX" to 4.0, "spanY" to 1.0,
                "appWidgetProvider" to "com.w/com.w.P"
            ),
            // In a drawer folder, which is no part of the home screen.
            favorite(6, 0, -207, "intent" to appIntent("com.x/.X")),
            // A kind this knows nothing of.
            favorite(7, 10, -100)
        )
        val layout = BackupLayouts.layout(
            BackupContents(
                tables = mapOf("favorites" to rows),
                prefs = mapOf(
                    "desktop_grid" to "12x10 subgrid",
                    "dock_grid_cols" to "6",
                    "workspace_screen_count" to "3",
                    "drawer_grid" to "6x5"
                )
            )
        )!!
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), layout.items.map { it.id })
        assertEquals(1, layout.unread)
        assertTrue(layout.literalPages)
        val grid = ForeignGrid(
            cols = 10, rows = 12, dockSlots = 6, pageCount = 3, drawerCols = 5, drawerRows = 6, subgrid = true
        )
        assertEquals(grid, layout.grid)

        val mail = layout.items[0]
        assertEquals(ForeignKind.APP, mail.kind)
        assertEquals(1, mail.page)
        assertEquals(9.5f, mail.x)
        assertEquals(0L, mail.userSerial)
        assertEquals("com.mail" to "com.mail.Main", mail.intent?.component)

        val docked = layout.items[1]
        assertEquals(ForeignPlace.DOCK, docked.place)
        assertEquals(1, docked.page)
        assertEquals(3f, docked.x)

        val member = layout.items[3]
        assertEquals(ForeignPlace.FOLDER, member.place)
        assertEquals(3L, member.parentId)
        assertEquals(10L, member.userSerial)

        assertEquals("com.w/com.w.P", layout.items[4].widgetProvider)
    }

    @Test
    fun plainFamilyReadsDockSlotFromScreenAndGridFromDetails() {
        val rows = listOf(
            favorite(
                1, 0, -101, "screen" to 3L, "cellX" to 3L, "cellY" to 0L, "intent" to appIntent("com.phone/.Dial")
            ),
            favorite(
                2, 6, -100, "screen" to 4L, "cellX" to 1L, "cellY" to 2L, "title" to "Home route",
                "intent" to "#Intent;action=android.intent.action.MAIN;package=com.maps;S.shortcut_id=home;end"
            )
        )
        val grid = text(1, "4,6") + number(2, 5)
        val settings = bytes(1, text(1, "drawer_columns") + bytes(2, number(3, 6))) +
            bytes(1, text(1, "folder_columns") + bytes(2, number(3, 3))) +
            bytes(1, text(1, "some_flag") + bytes(2, number(1, 1)))
        val layout = BackupLayouts.layout(
            BackupContents(
                tables = mapOf("favorites" to rows),
                prefs = mapOf("pref_workspaceColumns" to "9", "migration_src_hotseat_count" to "7"),
                info = bytes(7, grid),
                settings = settings
            )
        )!!
        assertFalse(layout.literalPages)
        assertEquals(ForeignGrid(cols = 4, rows = 6, dockSlots = 5, drawerCols = 6, folderCols = 3), layout.grid)
        assertEquals(0, layout.items[0].page)
        assertEquals(3f, layout.items[0].x)
        assertEquals(ForeignKind.APP_SHORTCUT, layout.items[1].kind)
        assertEquals("home", layout.items[1].shortcutId)
        assertEquals("com.maps", layout.items[1].intent?.targetPackage)
    }

    @Test
    fun plainFamilyFallsBackToPreferencesForItsGrid() {
        val layout = BackupLayouts.layout(
            BackupContents(
                tables = mapOf("favorites" to emptyList()),
                prefs = mapOf("pref_workspaceColumns" to "5", "pref_workspaceRows" to "7", "pref_hotseatColumns" to "4")
            )
        )!!
        assertEquals(ForeignGrid(cols = 5, rows = 7, dockSlots = 4), layout.grid)
    }

    // --- Placed items split from what they launch

    // A spot: its id, layout and screen, then its cell, then what sits there.
    private fun placed(at: List<Long>, x: Double, y: Double, launchable: Long? = null, widget: Long? = null) = Row(
        mapOf(
            "id" to at[0], "idLaunchable" to launchable, "idWidget" to widget, "screen" to at[2],
            "configId" to at[1], "cellX" to x, "cellY" to y, "spanX" to 1.0, "spanY" to 1.0
        )
    )

    private fun action(launchable: Long, type: Long, intent: String?, label: String, vararg more: Pair<String, Any?>) =
        Row(
            mapOf(
                "idLaunchable" to launchable, "actionId" to 1L, "type" to type, "intentUri" to intent,
                "userId" to 0L, "label" to label, "deepShortcutId" to null
            ) + more
        )

    private fun widget(id: Long, provider: String, appWidgetId: Long?) =
        Row(mapOf("id" to id, "type" to 4L, "appWidgetId" to appWidgetId, "provider" to provider, "userId" to -1L))

    // The shape of a real backup: icons and widgets in one layout, icons
    // that stand for a kind of app, the launcher's own widgets beside an
    // app's, a second page holding only its own widgets.
    private fun oneLayout() = BackupContents(
        tables = mapOf(
            "homeitem" to listOf(
                placed(listOf(1, 0, 0), 0.0, 0.0, widget = 1),
                placed(listOf(2, 0, 1), 0.0, 0.0, widget = 2),
                placed(listOf(3, 0, 0), 0.0, 3.0, widget = 3),
                placed(listOf(4, 0, 0), 0.0, 6.0, launchable = 1),
                placed(listOf(5, 0, 0), 1.0, 6.0, launchable = 2),
                placed(listOf(6, 0, 0), 2.0, 5.0, launchable = 7),
                placed(listOf(7, 0, 0), 3.0, 5.0, launchable = 8),
                placed(listOf(8, 0, 0), 0.0, 5.0, launchable = 14)
            ),
            "launchable" to listOf(
                Row(mapOf("id" to 8L, "idParentFolderLaunchable" to null, "position" to -1L)),
                Row(mapOf("id" to 9L, "idParentFolderLaunchable" to 8L, "position" to 1L)),
                Row(mapOf("id" to 10L, "idParentFolderLaunchable" to 8L, "position" to 0L))
            ),
            "action" to listOf(
                // The dialer and the music player, each with its app.
                action(1, 0, "#Intent;component=com.phone/.Dial;end", "Phone"),
                action(2, 1, "#Intent;component=com.music/.Player;end", "Music"),
                action(7, 7, appIntent("com.mail/.Main"), "Mail"),
                action(8, 9, "#Intent;action=open.folder;i.folderId=8;end", "Social", "userId" to -1L),
                action(9, 7, appIntent("com.docs/.Docs"), "Docs"),
                action(10, 7, appIntent("com.sheets/.Sheets"), "Sheets", "userId" to 10L),
                action(14, 6, "#Intent;action=view;end", "Route", "deepShortcutId" to "route-1")
            ),
            "widget" to listOf(
                widget(1, "com.launcher/com.launcher.ClockWidget", appWidgetId = null),
                widget(2, "com.launcher/com.launcher.NewsWidget", appWidgetId = null),
                widget(3, "com.clock/com.clock.Provider", appWidgetId = 12)
            )
        ),
        prefs = mapOf(
            "launcher_profile_#0" to "{\"id\":0,\"defaultHomePage\":0," +
                "\"gridUserSettings\":{\"columns\":4,\"rows\":8,\"hasLabel\":false}," +
            "\"drawerSettings\":{\"portraitColumns\":5,\"landscapeColumns\":8}}"
    )
    )

    @Test
    fun splitFamilyReadsIconsFoldersAndWidgetsOfOneLayout() {
        val layout = BackupLayouts.layout(oneLayout())!!
        assertFalse(layout.literalPages)
        assertEquals(0, layout.homePage)
        assertEquals(ForeignGrid(cols = 4, rows = 8, drawerCols = 5), layout.grid)
        // The launcher's own two widgets.
        assertEquals(2, layout.unread)
        assertTrue(layout.items.all { it.place != ForeignPlace.DOCK })

        val byTitle = layout.items.associateBy { it.title }
        val phone = byTitle.getValue("Phone")
        assertEquals(ForeignKind.APP, phone.kind)
        assertEquals("com.phone" to "com.phone.Dial", phone.intent?.component)
        assertEquals(listOf(0f, 6f), listOf(phone.x, phone.y))
        assertEquals(ForeignKind.APP, byTitle.getValue("Music").kind)
        assertEquals(ForeignKind.APP, byTitle.getValue("Mail").kind)

        val folder = byTitle.getValue("Social")
        assertEquals(ForeignKind.FOLDER, folder.kind)
        assertEquals(0L, folder.userSerial)
        assertEquals(folder.id, byTitle.getValue("Docs").parentId)
        assertEquals(1, byTitle.getValue("Docs").rank)
        assertEquals(0, byTitle.getValue("Sheets").rank)
        assertEquals(10L, byTitle.getValue("Sheets").userSerial)

        assertEquals(ForeignKind.APP_SHORTCUT, byTitle.getValue("Route").kind)
        assertEquals("route-1", byTitle.getValue("Route").shortcutId)

        val widget = layout.items.single { it.kind == ForeignKind.WIDGET }
        assertEquals("com.clock/com.clock.Provider", widget.widgetProvider)
        assertEquals(3f, widget.y)
    }

    @Test
    fun splitFamilyReadsADockFromALayoutOfItsOwn() {
        val contents = BackupContents(
            tables = mapOf(
                "homeitem" to listOf(
                    placed(listOf(1, 1, -1), 0.0, 0.0, launchable = 10),
                    placed(listOf(2, 1, 0), 2.0, 3.0, launchable = 11),
                    placed(listOf(3, 1, 1), 0.0, 0.0, launchable = 12),
                    // The dock: two rows of four on one screen.
                    placed(listOf(5, 2, 0), 0.0, 1.0, launchable = 20),
                    placed(listOf(6, 2, 0), 3.0, 0.0, launchable = 21),
                    // Another profile's layout, too wide for the dock.
                    placed(listOf(7, 3, 0), 6.0, 0.0, launchable = 30)
                ),
                "action" to listOf(
                    action(10, 7, appIntent("com.mail/.Main"), "Mail"),
                    action(11, 7, appIntent("com.docs/.Docs"), "Docs"),
                    action(12, 7, appIntent("com.sheets/.Sheets"), "Sheets"),
                    action(20, 7, appIntent("com.phone/.Dial"), "Phone"),
                    action(21, 7, appIntent("com.cam/.Cam"), "Camera"),
                    action(30, 7, appIntent("com.other/.Other"), "Other")
                )
            ),
            prefs = mapOf(
                "launcher_profile_#0" to "{\"name\":\"main\",\"layout\":{" +
                    "\"gridUserSettings\":{\"columns\":5,\"rows\":7},\"dockSettings\":{\"columns\":4,\"rows\":2}}}"
            )
        )
        val layout = BackupLayouts.layout(contents)!!
        assertEquals(ForeignGrid(cols = 5, rows = 7, dockSlots = 4), layout.grid)
        val byTitle = layout.items.associateBy { it.title }
        assertEquals(-1, byTitle.getValue("Mail").page)
        // The dock's bottom row is its first page.
        val phone = byTitle.getValue("Phone")
        assertEquals(ForeignPlace.DOCK, phone.place)
        assertEquals(0, phone.page)
        assertEquals(1, byTitle.getValue("Camera").page)
        assertEquals(3f, byTitle.getValue("Camera").x)
        assertNull(byTitle["Other"])
    }

    @Test
    fun noKnownTableIsNoLayout() {
        assertNull(BackupLayouts.layout(BackupContents(tables = mapOf("launchable" to emptyList()))))
        assertNotNull(BackupLayouts.layout(BackupContents(tables = mapOf("favorites" to emptyList()))))
    }
}
