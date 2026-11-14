package com.supernova.launcher.data.imports

import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.LauncherRepository
import com.supernova.launcher.data.Pref
import com.supernova.launcher.gesture.ActionShortcut
import com.supernova.launcher.gesture.GestureAction
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// Another launcher's home screen as Supernova items and settings: every icon,
// folder and widget on the page and cells it had, the dock in its slots, and
// the grids sized to hold them. Settings the backup says nothing about stay
// as they are.
internal object LayoutConverter {

    class Result(
        val items: List<LauncherItem>,
        val pageCount: Int,
        val defaultPage: Int,
        val prefs: LauncherPrefs,
        // Things Supernova has no place for: a widget in the dock, a folder
        // in a folder, an action it doesn't have.
        val skipped: Int,
        // Widgets whose app is not on this phone.
        val missing: Int
    )

    // What the conversion has to ask the phone.
    class Phone(
        // The activity to launch for an app whose own is gone or unnamed.
        val activityFor: (packageName: String, className: String?, userSerial: Long) -> String? =
            { _, className, _ -> className },
        // A kept icon as the text LauncherItem.iconBase64 holds.
        val encodeIcon: (ByteArray) -> String? = { null },
        // The widget's name, null where its app is not installed.
        val widgetName: (provider: String, userSerial: Long) -> String? = { provider, _ -> provider },
        // Whether the app is on this phone, for the drawer's tabs and folders.
        val installed: (packageName: String, userSerial: Long) -> Boolean = { _, _ -> true },
        val newId: () -> String = { UUID.randomUUID().toString() }
    )

    fun convert(layout: ForeignLayout, base: LauncherPrefs, phone: Phone = Phone()): Result =
        Placement(layout, base, ItemMaker(phone)).result()

    // Where everything goes.
    private class Placement(val layout: ForeignLayout, val base: LauncherPrefs, val maker: ItemMaker) {
        val desktop = layout.items.filter { it.place == ForeignPlace.DESKTOP }
        val dock = layout.items.filter { it.place == ForeignPlace.DOCK }
        val members = layout.items.filter { it.place == ForeignPlace.FOLDER }.groupBy { it.parentId }
        val out = ArrayList<LauncherItem>()
        var skipped = 0
        var halfCells = false
        val pages = pageNumbers()
        val cols = gridSize(layout.grid.cols ?: base.gridCols, desktop.maxOfOrNull { it.x + it.spanX })
        val rows = gridSize(layout.grid.rows ?: base.gridRows, desktop.maxOfOrNull { it.y + it.spanY })
        val dockCount = Pref.dockCount.clamped(
            max(layout.grid.dockSlots ?: base.dockCount, (dock.maxOfOrNull { slot(it) } ?: 0) + 1)
        )

        fun result(): Result {
            desktop.forEach { item -> pages[item.page]?.let { page -> place(item, onDesktop(item, page)) } }
            val dockPages = placeDock()
            // What sat in a folder that is itself nowhere.
            val folders = layout.items
                .filter { it.kind == ForeignKind.FOLDER && it.place != ForeignPlace.FOLDER }
                .map { it.id }
            skipped += members.filterKeys { it !in folders }.values.sumOf { it.size }
            // A widget whose app is gone was counted as it was passed over.
            val leftOut = skipped + layout.unread - maker.missing
            val shown = shownPages()
            val items = out.map { item ->
                if (item.container == ContainerType.WORKSPACE) item.copy(page = shown[item.page] ?: 0) else item
            }
            val pageCount = max(shown.values.maxOfOrNull { it + 1 } ?: 1, layout.grid.pageCount ?: 1)
                .coerceIn(1, LauncherRepository.MAX_PAGES)
            val home = pages[layout.homePage]?.let(shown::get) ?: 0
            return Result(items, pageCount, home, prefs(dockPages), leftOut, maker.missing)
        }

        // Where each page ends up. Real page numbers stay; ids that only
        // order the pages close up once more, over a page that got nothing.
        private fun shownPages(): Map<Int, Int> {
            val used = out.filter { it.container == ContainerType.WORKSPACE }.map { it.page }.distinct().sorted()
            return if (layout.literalPages) {
                pages.values.associateWith { it }
            } else {
                used.withIndex().associate { it.value to it.index }
            }
        }

        // A backup with the drawer's tabs and folders has them as that
        // launcher sorted them: Smart Folders go off, so no folder made here
        // sorts the same apps a second time.
        private fun prefs(dockPages: Int): LauncherPrefs {
            val grid = layout.grid
            val groups = layout.groups
            return base.copy(
                gridCols = cols,
                gridRows = rows,
                dockCount = dockCount,
                dockPages = Pref.dockPages.clamped(dockPages),
                dockEnabled = base.dockEnabled || dock.isNotEmpty(),
                subgrid = base.subgrid || grid.subgrid || halfCells,
                drawerColumns = grid.drawerCols?.let { Pref.drawerColumns.clamped(max(1, it)) } ?: base.drawerColumns,
                drawerRows = grid.drawerRows?.let { Pref.drawerRows.clamped(max(1, it)) } ?: base.drawerRows,
                folderColumns = grid.folderCols?.let { Pref.folderColumns.clamped(max(1, it)) } ?: base.folderColumns,
                smartFolders = base.smartFolders && groups == null,
                drawerGroups = groups?.let { DrawerGroupConverter.merged(it, base.drawerGroups, maker.phone) }
                    ?: base.drawerGroups
            )
        }

        // Page numbers as Supernova counts them. Ids that only order the
        // pages close up; real page numbers keep their gaps, past the last
        // page Supernova can have they are left out.
        private fun pageNumbers(): Map<Int, Int> {
            val used = desktop.map { it.page }.distinct().sorted()
            val numbered = if (layout.literalPages) {
                used.filter { it >= 0 }.associateWith { it }
            } else {
                used.withIndex().associate { it.value to it.index }
            }
            val kept = numbered.filterValues { it < LauncherRepository.MAX_PAGES }
            skipped += desktop.count { it.page !in kept }
            return kept
        }

        private fun gridSize(stated: Int, reach: Float?): Int =
            Pref.gridCols.clamped(max(stated, ceil((reach ?: 0f) - EDGE).toInt()))

        private fun slot(item: ForeignItem): Int = item.x.roundToInt().coerceAtLeast(0)

        // The item where the given copy puts it, with what a folder holds;
        // no copy, and it and they are left out.
        private fun place(source: ForeignItem, placed: LauncherItem?) {
            val inside = members[source.id].orEmpty().takeIf { source.kind == ForeignKind.FOLDER }.orEmpty()
            if (placed == null) {
                skipped += 1 + inside.size
            } else {
                out += placed
                val made = inside.sortedWith(compareBy({ it.rank }, { it.y }, { it.x }))
                    .mapNotNull { member -> member.takeIf { it.kind !in NOT_IN_FOLDERS }?.let(maker::make) }
                skipped += inside.size - made.size
                made.forEachIndexed { rank, member ->
                    out += member.copy(container = ContainerType.FOLDER, folderId = placed.id, rank = rank)
                }
            }
        }

        private fun onDesktop(item: ForeignItem, page: Int): LauncherItem? {
            val x = halves(item.x)
            val y = halves(item.y)
            val onCells = x % 2 == 0 && y % 2 == 0
            return maker.make(item)?.copy(page = page)?.let { made ->
                when {
                    item.kind == ForeignKind.WIDGET -> widgetOn(made, x, y, halves(item.spanX), halves(item.spanY))
                    onCells -> made.copy(cellX = x / 2, cellY = y / 2)
                    else -> {
                        halfCells = true
                        made.copy(
                            cellX = x / 2,
                            cellY = y / 2,
                            workspaceX = (x / 2f + HALF) / cols,
                            workspaceY = (y / 2f + HALF) / rows
                        )
                    }
                }
            }
        }

        // A widget on its cells, given in half cells: whole ones where
        // every edge sits on one.
        private fun widgetOn(made: LauncherItem, x: Int, y: Int, spanX: Int, spanY: Int): LauncherItem {
            val w = spanX.coerceAtLeast(1)
            val h = spanY.coerceAtLeast(1)
            val whole = listOf(x, y, w, h).all { it % 2 == 0 }
            val unit = if (whole) 2 else 1
            return made.copy(
                cellX = x / unit, cellY = y / unit, spanX = w / unit, spanY = h / unit, halfCells = !whole
            )
        }

        // Each dock item in its slot, the first to claim one keeping it and
        // the next taking the nearest free slot on the page; returns the
        // number of dock pages used.
        private fun placeDock(): Int {
            val taken = HashSet<Int>()
            var lastPage = 0
            dock.sortedWith(compareBy({ it.page }, { slot(it) })).forEach { item ->
                val page = item.page.coerceAtLeast(0)
                val free = (0 until dockCount)
                    .filter { page * dockCount + it !in taken }
                    .minByOrNull { abs(it - slot(item)) }
                val docks = item.kind != ForeignKind.WIDGET && page < Pref.dockPages.clamped(page + 1)
                val made = item.takeIf { docks && free != null }?.let(maker::make)
                if (made != null && free != null) {
                    taken += page * dockCount + free
                    lastPage = max(lastPage, page)
                }
                place(item, made?.copy(container = ContainerType.DOCK, rank = page * dockCount + (free ?: 0)))
            }
            return lastPage + 1
        }
    }

    // One of the other launcher's items as Supernova's own, not yet anywhere.
    private class ItemMaker(val phone: Phone) {
        var missing = 0
            private set

        // Null for what Supernova can't hold.
        fun make(item: ForeignItem): LauncherItem? {
            val start = LauncherItem(
                id = phone.newId(), itemType = ItemType.APP, label = item.title, userSerial = item.userSerial
            )
            return when (item.kind) {
                // A folder keeps an icon only where it was given one.
                ForeignKind.FOLDER ->
                    start.copy(itemType = ItemType.FOLDER, iconBase64 = item.icon?.let(phone.encodeIcon))
                ForeignKind.WIDGET -> widget(item, start)
                ForeignKind.APP -> app(item, start)
                ForeignKind.APP_SHORTCUT -> appShortcut(item, start)
                ForeignKind.INTENT -> intent(item, start)
            }
        }

        private fun widget(item: ForeignItem, start: LauncherItem): LauncherItem? {
            val provider = item.widgetProvider?.takeIf { IntentText.component(it) != null }
            val name = provider?.let { phone.widgetName(it, item.userSerial) }
            if (provider != null && name == null) missing++
            return name?.let {
                start.copy(itemType = ItemType.WIDGET, widgetProvider = provider, label = item.title.ifEmpty { it })
            }
        }

        private fun app(item: ForeignItem, start: LauncherItem): LauncherItem? {
            val pkg = item.intent?.targetPackage
            val cls = pkg?.let { phone.activityFor(it, item.intent?.component?.second, item.userSerial) }
            return cls?.let { start.copy(packageName = pkg, className = it) }
        }

        private fun appShortcut(item: ForeignItem, start: LauncherItem): LauncherItem? {
            val pkg = item.intent?.targetPackage
            val id = item.shortcutId?.takeIf { it.isNotEmpty() }
            return if (pkg == null || id == null) {
                null
            } else {
                start.copy(
                    itemType = ItemType.SHORTCUT,
                    packageName = pkg,
                    className = phone.activityFor(pkg, item.intent?.component?.second, item.userSerial),
                    shortcutId = id,
                    iconBase64 = item.icon?.let(phone.encodeIcon)
                )
            }
        }

        private fun intent(item: ForeignItem, start: LauncherItem): LauncherItem? {
            val intent = item.intent
            val ownAction = intent?.extras?.get(OWN_ACTION)
            return when {
                intent == null -> null
                // One of the other launcher's own actions: Supernova's twin
                // of it, or nothing.
                ownAction != null -> ownAction(ownAction)?.let {
                    start.copy(itemType = ItemType.SHORTCUT, intentUri = ActionShortcut.encode(it))
                }
                // An app's own entry, kept as an intent.
                intent.action == ACTION_MAIN && CATEGORY_LAUNCHER in intent.categories && intent.component != null ->
                    app(item, start)
                else -> start.copy(
                    itemType = ItemType.SHORTCUT, intentUri = intent.uri, iconBase64 = item.icon?.let(phone.encodeIcon)
                )
            }
        }
    }

    private fun halves(cells: Float): Int = (cells * 2f).roundToInt().coerceAtLeast(0)

    // The launcher's own settings screen is named after it, so by its ending.
    private fun ownAction(name: String): GestureAction? =
        OWN_ACTIONS[name] ?: GestureAction.OPEN_SETTINGS.takeIf { name.endsWith("_SETTINGS") }

    private val OWN_ACTIONS = mapOf(
        "APP_DRAWER" to GestureAction.OPEN_DRAWER,
        "APP_SEARCH" to GestureAction.OPEN_SEARCH,
        "TEXT_SEARCH" to GestureAction.OPEN_SEARCH,
        "EXPAND_STATUS_BAR" to GestureAction.EXPAND_NOTIFICATIONS,
        "EXPAND_STATUS_SETTINGS_BAR" to GestureAction.QUICK_SETTINGS,
        "SHOW_RECENT_APPS" to GestureAction.RECENTS,
        "SHOW_PREVIEWS" to GestureAction.DESKTOP_PREVIEWS,
        "SCREEN_OFF" to GestureAction.LOCK_SCREEN,
        "GOTO_DEFAULT_SCREEN" to GestureAction.DEFAULT_PAGE,
        "TOGGLE_DOCK" to GestureAction.TOGGLE_DOCK,
        "TOGGLE_STATUS_BAR" to GestureAction.TOGGLE_STATUS_BAR,
        "ASSIST" to GestureAction.ASSISTANT
    )
    private val NOT_IN_FOLDERS = setOf(ForeignKind.FOLDER, ForeignKind.WIDGET)
    private const val EDGE = 0.05f
    private const val HALF = 0.5f
    private const val OWN_ACTION = "LAUNCHER_ACTION"
    private const val ACTION_MAIN = "android.intent.action.MAIN"
    private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
}
