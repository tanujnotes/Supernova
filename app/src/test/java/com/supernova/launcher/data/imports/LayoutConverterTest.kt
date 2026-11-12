package com.supernova.launcher.data.imports

import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LauncherModel
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.gesture.ActionShortcut
import com.supernova.launcher.gesture.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Another launcher's home screen turned into Supernova's items and settings.
class LayoutConverterTest {

    private var nextId = 0
    private val phone = LayoutConverter.Phone(
        encodeIcon = { bytes -> "icon:${bytes.size}" },
        newId = { "id${nextId++}" }
    )

    private fun intent(component: String) = IntentText.parse(
        "#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;component=$component;end"
    )

    private fun app(id: Long, component: String, page: Int = 0, x: Float = 0f, y: Float = 0f) = ForeignItem(
        id, ForeignKind.APP, ForeignPlace.DESKTOP, page = page, x = x, y = y,
        title = component.substringBefore('/'), intent = intent(component)
    )

    private fun docked(id: Long, component: String, x: Float, page: Int = 0) =
        app(id, component, page, x).copy(place = ForeignPlace.DOCK)

    private fun member(id: Long, component: String, parent: Long, rank: Int) =
        app(id, component).copy(place = ForeignPlace.FOLDER, parentId = parent, rank = rank)

    private fun convert(
        items: List<ForeignItem>,
        grid: ForeignGrid = ForeignGrid(),
        base: LauncherPrefs = LauncherPrefs()
    ) = LayoutConverter.convert(ForeignLayout(items, grid), base, phone)

    @Test
    fun iconsKeepTheirPageAndCell() {
        val result = convert(
            listOf(app(1, "com.mail/.Main", page = 2, x = 3f, y = 4f)),
            ForeignGrid(cols = 4, rows = 5, dockSlots = 4, pageCount = 4)
        )
        val item = result.items.single()
        assertEquals(ItemType.APP, item.itemType)
        assertEquals(ContainerType.WORKSPACE, item.container)
        assertEquals(listOf(2, 3, 4), listOf(item.page, item.cellX, item.cellY))
        assertNull(item.workspaceX)
        assertEquals("com.mail", item.packageName)
        assertEquals("com.mail.Main", item.className)
        assertEquals(4, result.pageCount)
        assertEquals(4, result.prefs.gridCols)
        assertEquals(5, result.prefs.gridRows)
        assertEquals(4, result.prefs.dockCount)
        assertFalse(result.prefs.subgrid)
    }

    @Test
    fun halfCellIconsLandWhereTheyWereAndTurnSubgridOn() {
        val result = convert(listOf(app(1, "com.mail/.Main", x = 9.5f, y = 2.5f)), ForeignGrid(cols = 12, rows = 12))
        val item = result.items.single()
        assertTrue(result.prefs.subgrid)
        val rect = WorkspaceGeometry.itemRect(item, 12, 12)
        assertEquals(9.5f, rect.left, 0.001f)
        assertEquals(2.5f, rect.top, 0.001f)
        // Already on the grid in use, so nothing moves it.
        val model = LauncherModel(items = result.items)
        assertEquals(model, WorkspaceGeometry.fitted(model, 12, 12))
    }

    @Test
    fun widgetsKeepTheirSpansWholeOrHalf() {
        val noProvider = ForeignItem(3, ForeignKind.WIDGET, ForeignPlace.DESKTOP)
        val whole = noProvider.copy(id = 1, x = 1f, y = 2f, spanX = 4f, spanY = 2f, widgetProvider = "com.w/com.w.P")
        val half = noProvider.copy(id = 2, x = 0.5f, y = 4f, spanX = 2.5f, spanY = 1f, widgetProvider = "com.w/com.w.Q")
        val gone = noProvider.copy(id = 4, y = 5f, widgetProvider = "com.gone/com.gone.P")
        val named = LayoutConverter.Phone(
            widgetName = { provider, _ -> "Clock".takeIf { !provider.startsWith("com.gone") } }, newId = phone.newId
        )
        val layout = ForeignLayout(listOf(whole, half, noProvider, gone), ForeignGrid(cols = 5, rows = 6))
        val result = LayoutConverter.convert(layout, LauncherPrefs(), named)
        assertEquals(2, result.items.size)
        assertEquals(1, result.skipped)
        assertEquals(1, result.missing)
        val first = result.items[0]
        assertEquals("Clock", first.label)
        assertEquals(ItemType.WIDGET, first.itemType)
        assertNull(first.appWidgetId)
        assertEquals("com.w/com.w.P", first.widgetProvider)
        assertEquals(listOf(1, 2, 4, 2), listOf(first.cellX, first.cellY, first.spanX, first.spanY))
        assertFalse(first.halfCells)
        val second = result.items[1]
        assertTrue(second.halfCells)
        assertEquals(WorkspaceGeometry.CellRect(0.5f, 4f, 3f, 5f), WorkspaceGeometry.itemRect(second, 5, 6))
    }

    @Test
    fun gridGrowsToHoldWhatAnUnstatedOneHeld() {
        val base = LauncherPrefs(gridCols = 5, gridRows = 6)
        val result = convert(listOf(app(1, "com.a/.A", x = 6f, y = 7f)), base = base)
        assertEquals(7, result.prefs.gridCols)
        assertEquals(8, result.prefs.gridRows)
    }

    @Test
    fun foldersHoldTheirAppsInOrder() {
        val folder = ForeignItem(
            5, ForeignKind.FOLDER, ForeignPlace.DESKTOP, x = 1f, y = 1f, title = "Tools", icon = ByteArray(7)
        )
        val result = convert(
            listOf(
                member(6, "com.b/.B", parent = 5, rank = 2),
                folder,
                member(7, "com.a/.A", parent = 5, rank = 1),
                // A folder in a folder, and an app whose folder is nowhere.
                ForeignItem(8, ForeignKind.FOLDER, ForeignPlace.FOLDER, parentId = 5),
                member(9, "com.c/.C", parent = 99, rank = 0)
            )
        )
        val placed = result.items.single { it.itemType == ItemType.FOLDER }
        assertEquals("Tools", placed.label)
        assertEquals("icon:7", placed.iconBase64)
        val members = result.items.filter { it.container == ContainerType.FOLDER }
        assertEquals(listOf("com.a", "com.b"), members.map { it.packageName })
        assertEquals(listOf(0, 1), members.map { it.rank })
        assertTrue(members.all { it.folderId == placed.id })
        assertEquals(2, result.skipped)
    }

    @Test
    fun dockItemsTakeTheirSlotsAndPages() {
        val result = convert(
            listOf(
                docked(1, "com.a/.A", x = 0f),
                docked(2, "com.b/.B", x = 4f),
                docked(3, "com.c/.C", x = 2f, page = 1),
                // The same slot twice: the second takes the one beside it.
                docked(4, "com.d/.D", x = 4f),
                ForeignItem(5, ForeignKind.WIDGET, ForeignPlace.DOCK, x = 1f, widgetProvider = "com.w/com.w.P")
            ),
            ForeignGrid(dockSlots = 5),
            LauncherPrefs(dockEnabled = false)
        )
        val ranks = result.items.associate { it.packageName to it.rank }
        assertEquals(mapOf("com.a" to 0, "com.b" to 4, "com.c" to 7, "com.d" to 3), ranks)
        assertTrue(result.items.all { it.container == ContainerType.DOCK })
        assertEquals(2, result.prefs.dockPages)
        assertTrue(result.prefs.dockEnabled)
        assertEquals(1, result.skipped)
    }

    @Test
    fun dockGrowsForASlotPastItsStatedCount() {
        val result = convert(listOf(docked(1, "com.a/.A", x = 6f)), ForeignGrid(dockSlots = 5))
        assertEquals(7, result.prefs.dockCount)
        assertEquals(6, result.items.single().rank)
    }

    @Test
    fun pageIdsCloseUpAndTheHomePageFollows() {
        val layout = ForeignLayout(
            listOf(app(1, "com.a/.A", page = -1), app(2, "com.b/.B", page = 0), app(3, "com.c/.C", page = 5)),
            ForeignGrid(),
            literalPages = false,
            homePage = 0
        )
        val result = LayoutConverter.convert(layout, LauncherPrefs(), phone)
        assertEquals(listOf(0, 1, 2), result.items.map { it.page })
        assertEquals(3, result.pageCount)
        assertEquals(1, result.defaultPage)
    }

    @Test
    fun aPageThatGetsNothingClosesUpWhereNumbersOnlyOrder() {
        val ownWidget = ForeignItem(2, ForeignKind.WIDGET, ForeignPlace.DESKTOP, page = 1)
        val layout = ForeignLayout(
            listOf(app(1, "com.a/.A", page = 0), ownWidget, app(3, "com.c/.C", page = 2)),
            ForeignGrid(),
            literalPages = false,
            homePage = 2
        )
        val result = LayoutConverter.convert(layout, LauncherPrefs(), phone)
        assertEquals(listOf(0, 1), result.items.map { it.page })
        assertEquals(2, result.pageCount)
        assertEquals(1, result.defaultPage)
        assertEquals(1, result.skipped)
    }

    @Test
    fun realPageNumbersKeepTheirGaps() {
        val result = convert(listOf(app(1, "com.a/.A", page = 0), app(2, "com.b/.B", page = 2)))
        assertEquals(listOf(0, 2), result.items.map { it.page })
        assertEquals(3, result.pageCount)
    }

    @Test
    fun shortcutsAndActionsBecomeSupernovasOwn() {
        val shortcut = ForeignItem(
            1, ForeignKind.APP_SHORTCUT, ForeignPlace.DESKTOP, title = "Route",
            intent = IntentText.parse("#Intent;action=android.intent.action.MAIN;package=com.maps;end"),
            shortcutId = "home", icon = ByteArray(3)
        )
        val drawer = ForeignItem(
            2, ForeignKind.INTENT, ForeignPlace.DESKTOP, x = 1f, title = "Apps",
            intent = IntentText.parse(
                "#Intent;action=some.ACTION;component=a.b/.Handler;S.LAUNCHER_ACTION=APP_DRAWER;end"
            )
        )
        val unknown = ForeignItem(
            3, ForeignKind.INTENT, ForeignPlace.DESKTOP, x = 2f,
            intent = IntentText.parse("#Intent;action=some.ACTION;S.LAUNCHER_ACTION=SOMETHING_ELSE;end")
        )
        val dial = ForeignItem(
            4, ForeignKind.INTENT, ForeignPlace.DESKTOP, x = 3f, title = "Call home", icon = ByteArray(5),
            intent = IntentText.parse("tel:123#Intent;action=android.intent.action.DIAL;end")
        )
        val launcherEntry = ForeignItem(
            5, ForeignKind.INTENT, ForeignPlace.DESKTOP, x = 4f, intent = intent("com.mail/.Main")
        )
        val result = convert(listOf(shortcut, drawer, unknown, dial, launcherEntry))

        val route = result.items[0]
        assertEquals(ItemType.SHORTCUT, route.itemType)
        assertEquals("com.maps", route.packageName)
        assertEquals("home", route.shortcutId)
        assertEquals("icon:3", route.iconBase64)

        assertEquals(GestureAction.OPEN_DRAWER, ActionShortcut.decode(result.items[1].intentUri)?.action)

        val call = result.items[2]
        assertEquals(ItemType.SHORTCUT, call.itemType)
        assertEquals("intent:123#Intent;scheme=tel;action=android.intent.action.DIAL;end", call.intentUri)
        assertEquals("icon:5", call.iconBase64)

        val mail = result.items[3]
        assertEquals(ItemType.APP, mail.itemType)
        assertEquals("com.mail.Main", mail.className)

        assertEquals(1, result.skipped)
    }

    @Test
    fun activityIsAskedOfThePhone() {
        val asking = LayoutConverter.Phone(activityFor = { pkg, _, _ -> "$pkg.Launcher" }, newId = phone.newId)
        val layout = ForeignLayout(listOf(app(1, "com.a/.Gone")), ForeignGrid())
        val result = LayoutConverter.convert(layout, LauncherPrefs(), asking)
        assertEquals("com.a.Launcher", result.items.single().className)
    }

    @Test
    fun otherSettingsStayAsTheyWere() {
        val base = LauncherPrefs(iconScale = 1.3f, drawerColumns = 4, folderColumns = 3, showLabels = false)
        val result = convert(listOf(app(1, "com.a/.A")), ForeignGrid(drawerCols = 6, drawerRows = 7), base)
        assertEquals(base.copy(drawerColumns = 6, drawerRows = 7), result.prefs)
    }
}
