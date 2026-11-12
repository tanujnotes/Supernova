package com.supernova.launcher.data.imports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// What a backup archive held, read into plain values: every layout table by
// its lowercase name, every preferences file's entries together, and the two
// protocol buffer files some backups carry.
internal class BackupContents(
    val tables: Map<String, List<Row>> = emptyMap(),
    val prefs: Map<String, String> = emptyMap(),
    // Backup details, the grid among them.
    val info: ByteArray? = null,
    // A typed preferences store.
    val settings: ByteArray? = null
)

// The home screen inside another launcher's backup. Two families are known:
// one table of placed items, as the open source launcher keeps and the
// launchers grown from it; and placed items split from what they launch.
internal object BackupLayouts {

    // Tables that mark an archive as a layout this can read.
    val TABLES = setOf(FavoritesLayout.TABLE) + SplitLayout.TABLES

    // Null when the archive holds no layout table this knows.
    fun layout(contents: BackupContents): ForeignLayout? {
        val favorites = contents.tables[FavoritesLayout.TABLE]
        return when {
            favorites != null -> FavoritesLayout.read(favorites, contents)
            SplitLayout.TABLES.take(2).all { it in contents.tables } -> SplitLayout(contents).read()
            else -> null
        }
    }

    // A work profile carries its serial number; anything below 1 is the
    // phone's owner.
    fun userSerial(value: Long?): Long = value?.takeIf { it > 0 } ?: 0L

    // "6x5" or "4,5", whatever follows after a space.
    fun size(text: String?, separator: Char): Pair<Int, Int>? {
        val numbers = text?.trim()?.substringBefore(' ')?.split(separator).orEmpty().map { it.trim().toIntOrNull() }
        val first = numbers.getOrNull(0)
        val second = numbers.getOrNull(1)
        return if (first != null && second != null) first to second else null
    }
}

// One table of placed items: a row each, saying what it is, where it sits
// and, as an intent, what it launches.
private object FavoritesLayout {
    const val TABLE = "favorites"

    private const val ON_DESKTOP = -100L
    private const val IN_DOCK = -101L
    private const val SHORTCUT_ID = "shortcut_id"
    private const val TYPE_WIDGET = 4
    private const val TYPE_APP_SHORTCUT = 6
    private val KINDS = mapOf(
        0 to ForeignKind.APP,
        1 to ForeignKind.INTENT,
        2 to ForeignKind.FOLDER,
        TYPE_WIDGET to ForeignKind.WIDGET,
        TYPE_APP_SHORTCUT to ForeignKind.APP_SHORTCUT
    )

    // A stacking order column comes with a dock that has pages and with
    // page numbers that are real ones; without it the dock is one row whose
    // "screen" is the slot, and pages are ids.
    private const val STACKING = "zOrder"

    private const val INFO_GRID = 7
    private const val GRID_SIZE = 1
    private const val GRID_DOCK = 2
    private const val VALUE_INT = 3

    fun read(rows: List<Row>, contents: BackupContents): ForeignLayout {
        val paged = rows.any { it.has(STACKING) }
        // Rows on the home screen; a drawer folder's are no part of it.
        val placed = rows.filter { place(it) != null }
        val items = placed.mapNotNull { item(it, paged) }
        return ForeignLayout(items, grid(contents), literalPages = paged, unread = placed.size - items.size)
    }

    private fun place(row: Row): ForeignPlace? {
        val container = row.long("container")
        return when {
            container == ON_DESKTOP -> ForeignPlace.DESKTOP
            container == IN_DOCK -> ForeignPlace.DOCK
            container != null && container > 0 -> ForeignPlace.FOLDER
            else -> null
        }
    }

    private fun item(row: Row, paged: Boolean): ForeignItem? {
        val id = row.long("_id")
        val kind = KINDS[row.int("itemType")]
        val place = place(row)
        val intent = IntentText.parse(row.text("intent"))
        val screen = row.int("screen") ?: 0
        val slotOnly = place == ForeignPlace.DOCK && !paged
        return if (id == null || kind == null || place == null) {
            null
        } else {
            ForeignItem(
                id = id,
                kind = kind,
                place = place,
                parentId = row.long("container").takeIf { place == ForeignPlace.FOLDER },
                page = if (slotOnly) 0 else screen,
                x = if (slotOnly) screen.toFloat() else row.float("cellX") ?: 0f,
                y = row.float("cellY") ?: 0f,
                spanX = row.float("spanX") ?: 1f,
                spanY = row.float("spanY") ?: 1f,
                rank = row.int("rank") ?: 0,
                title = row.text("title").orEmpty(),
                intent = intent,
                shortcutId = intent?.extras?.get(SHORTCUT_ID),
                widgetProvider = row.text("appWidgetProvider"),
                userSerial = BackupLayouts.userSerial(row.long("profileId")),
                icon = row.blob("icon")
            )
        }
    }

    private fun grid(contents: BackupContents): ForeignGrid {
        val prefs = contents.prefs
        // "rows x columns", then "subgrid" where icons sit on half cells.
        val desktop = BackupLayouts.size(prefs["desktop_grid"], 'x')
        val drawer = BackupLayouts.size(prefs["drawer_grid"], 'x')
        // "columns,rows" as the backup's own details state it, or as the
        // preferences remember it.
        val state = contents.info?.let(ProtoFields::parse)?.message(INFO_GRID)
        val stated = BackupLayouts.size(state?.text(GRID_SIZE), ',')
            ?: BackupLayouts.size(prefs["migration_src_workspace_size"], ',')
        val settings = typedSettings(contents.settings)
        return ForeignGrid(
            cols = desktop?.second ?: stated?.first ?: prefs["pref_workspaceColumns"]?.toIntOrNull(),
            rows = desktop?.first ?: stated?.second ?: prefs["pref_workspaceRows"]?.toIntOrNull(),
            dockSlots = dockSlots(prefs, state),
            pageCount = prefs["workspace_screen_count"]?.toIntOrNull(),
            drawerCols = drawer?.second ?: settings["drawer_columns"],
            drawerRows = drawer?.first,
            folderCols = settings["folder_columns"],
            subgrid = prefs["desktop_grid"]?.contains("subgrid") == true
        )
    }

    private fun dockSlots(prefs: Map<String, String>, state: ProtoFields?): Int? =
        prefs["dock_grid_cols"]?.toIntOrNull()
            ?: state?.int(GRID_DOCK)?.takeIf { it > 0 }
            ?: prefs["migration_src_hotseat_count"]?.toIntOrNull()
            ?: prefs["pref_hotseatColumns"]?.toIntOrNull()

    // A typed preferences store's whole numbers: entries of a name and a
    // value, the value's own field telling its type.
    private fun typedSettings(bytes: ByteArray?): Map<String, Int> {
        val entries = bytes?.let(ProtoFields::parse)?.messages(1).orEmpty()
        return entries.mapNotNull { entry ->
            val name = entry.text(1)
            val number = entry.message(2)?.int(VALUE_INT)
            if (name != null && number != null) name to number else null
        }.toMap()
    }
}

// Placed items split from what they launch: a row per spot, naming a
// launchable or a widget; a launchable's action says what it is, and a
// folder's apps are launchables that name it as their parent. Each layout id
// is a layout of its own: the home pages with their icons and widgets, and
// where there are more, a dock or another profile's pages.
private class SplitLayout(contents: BackupContents) {

    private class Sizes(
        val cols: Int?,
        val rows: Int?,
        val dockCols: Int?,
        val dockRows: Int?,
        val drawerCols: Int?,
        val homePage: Int
    )

    private val placed = contents.tables[PLACED].orEmpty()
    private val launchables = contents.tables[LAUNCHABLE].orEmpty()
    private val actions = contents.tables[ACTION].orEmpty()
        .sortedBy { if (it.int("actionId") == MAIN_ACTION) 0 else 1 }
        .groupBy { it.long("idLaunchable") }
        .mapValues { it.value.first() }
    private val widgets = contents.tables[WIDGET].orEmpty().associateBy { it.long("id") }
    private val sizes = sizes(contents.prefs)

    fun read(): ForeignLayout {
        val layouts = placed.groupBy { it.long("configId") }
        // The home pages are the layout holding the most; the dock is the
        // biggest other one that fits the dock's grid on one screen.
        val home = layouts.maxByOrNull { it.value.size }
        val dock = layouts.filter { it.key != home?.key }.values.filter(::fitsDock).maxByOrNull { it.size }.orEmpty()
        val dockBottom = dock.maxOfOrNull { it.float("cellY") ?: 0f } ?: 0f
        val spots = home?.value.orEmpty().map { it to desktopSpot(it) } + dock.map { it to dockSpot(it, dockBottom) }
        val read = spots.map { (row, spot) -> item(row, spot) }
        val items = read.filterNotNull().flatMap { listOf(it) + members(it) }
        return ForeignLayout(
            items,
            ForeignGrid(
                cols = sizes.cols, rows = sizes.rows, dockSlots = sizes.dockCols, drawerCols = sizes.drawerCols
            ),
            // Pages may run to both sides of the home page, and one may hold
            // nothing that comes over.
            literalPages = false,
            homePage = sizes.homePage,
            unread = read.count { it == null }
        )
    }

    private fun desktopSpot(row: Row) = ForeignItem(
        id = 0,
        kind = ForeignKind.APP,
        place = ForeignPlace.DESKTOP,
        page = row.int("screen") ?: 0,
        x = row.float("cellX") ?: 0f,
        y = row.float("cellY") ?: 0f,
        spanX = row.float("spanX") ?: 1f,
        spanY = row.float("spanY") ?: 1f
    )

    // A dock of several rows becomes dock pages, its bottom row the first.
    private fun dockSpot(row: Row, bottom: Float) = ForeignItem(
        id = 0,
        kind = ForeignKind.APP,
        place = ForeignPlace.DOCK,
        page = (bottom - (row.float("cellY") ?: 0f)).toInt(),
        x = row.float("cellX") ?: 0f
    )

    private fun item(row: Row, spot: ForeignItem): ForeignItem? {
        val launchable = row.long("idLaunchable")?.takeIf { it > 0 }
        val widget = row.long("idWidget")
        return when {
            launchable != null -> launchable(launchable, spot)
            // Widget ids are their own; below zero they can't meet a
            // launchable's.
            widget != null -> provider(widgets[widget])?.let {
                spot.copy(
                    id = -widget - 1,
                    kind = ForeignKind.WIDGET,
                    widgetProvider = it,
                    userSerial = BackupLayouts.userSerial(widgets[widget]?.long("userId"))
                )
            }
            else -> null
        }
    }

    // What a folder holds; nothing for anything else.
    private fun members(folder: ForeignItem): List<ForeignItem> =
        launchables.takeIf { folder.kind == ForeignKind.FOLDER }.orEmpty()
            .filter { it.long("idParentFolderLaunchable") == folder.id }
            .mapNotNull { member ->
                val inside = ForeignItem(
                    id = 0, kind = ForeignKind.APP, place = ForeignPlace.FOLDER,
                    parentId = folder.id, rank = member.int("position") ?: 0
                )
                member.long("id")?.let { launchable(it, inside) }
            }

    private fun launchable(id: Long, spot: ForeignItem): ForeignItem? {
        val action = actions[id]
        val intent = IntentText.parse(action?.text("intentUri"))
        val shortcutId = action?.text("deepShortcutId")?.takeIf { it.isNotBlank() }
        val kind = when {
            action == null -> null
            action.int("type") == TYPE_FOLDER -> ForeignKind.FOLDER
            shortcutId != null -> ForeignKind.APP_SHORTCUT
            // An app, or an icon standing for a kind of app (the dialer,
            // the browser) with the app picked for it.
            action.int("type") in APP_TYPES && intent?.component != null -> ForeignKind.APP
            intent != null -> ForeignKind.INTENT
            else -> null
        }
        return kind?.let {
            spot.copy(
                id = id,
                kind = it,
                title = action?.text("label").orEmpty(),
                intent = intent,
                shortcutId = shortcutId,
                userSerial = BackupLayouts.userSerial(action?.long("userId"))
            )
        }
    }

    // An app widget's provider, "package/class". The launcher's own
    // built-in widgets (its clock, its search bar) sit in the same table,
    // with no app widget id: they are nothing another launcher can show.
    private fun provider(widget: Row?): String? =
        widget?.takeIf { it.long("appWidgetId") != null }?.text("provider")?.takeIf { IntentText.component(it) != null }

    private fun fitsDock(rows: List<Row>): Boolean {
        val cols = sizes.dockCols ?: 0
        val deep = sizes.dockRows ?: 1
        return cols > 0 && rows.map { it.int("screen") }.distinct().size == 1 && rows.all { row ->
            (row.float("cellX") ?: 0f) + (row.float("spanX") ?: 1f) <= cols + EDGE &&
                (row.float("cellY") ?: 0f) + (row.float("spanY") ?: 1f) <= deep + EDGE
        }
    }

    companion object {
        const val PLACED = "homeitem"
        const val ACTION = "action"
        const val LAUNCHABLE = "launchable"
        const val WIDGET = "widget"

        // The two a layout can't be read without, then the rest.
        val TABLES = listOf(PLACED, ACTION, LAUNCHABLE, WIDGET)

        private const val TYPE_FOLDER = 9
        private const val TYPE_APP = 7
        // 0 to 5 are the kinds of app an icon can stand for.
        private val APP_TYPES = (0..5) + TYPE_APP
        private const val MAIN_ACTION = 1
        private const val EDGE = 0.05f
        // The first layout profile's grids, wherever in its document they
        // sit.
        private fun sizes(prefs: Map<String, String>): Sizes {
            val profiles = prefs.keys.filter { it.startsWith("launcher_profile_") }.sorted()
            val profile = profiles.firstNotNullOfOrNull { key ->
                runCatching { Json.parseToJsonElement(prefs.getValue(key)) }.getOrNull()
            }
            val grid = find(profile, "gridUserSettings")
            val dock = find(profile, "dockSettings")
            return Sizes(
                cols = number(grid, "columns"),
                rows = number(grid, "rows"),
                dockCols = number(dock, "columns"),
                dockRows = number(dock, "rows"),
                drawerCols = number(find(profile, "drawerSettings"), "portraitColumns"),
                homePage = ((profile as? JsonObject)?.get("defaultHomePage") as? JsonPrimitive)?.intOrNull ?: 0
            )
        }

        private fun find(element: JsonElement?, name: String): JsonObject? = when (element) {
            is JsonObject -> (element[name] as? JsonObject) ?: element.values.firstNotNullOfOrNull { find(it, name) }
            is JsonArray -> element.firstNotNullOfOrNull { find(it, name) }
            else -> null
        }

        private fun number(holder: JsonObject?, name: String): Int? =
            (holder?.get(name) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    }
}
