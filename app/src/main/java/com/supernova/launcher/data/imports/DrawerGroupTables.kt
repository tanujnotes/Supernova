package com.supernova.launcher.data.imports

// The app drawer's tabs and folders inside another launcher's backup. Three
// shapes are known: a groups table whose rows say whether each is a tab or a
// folder, with a table of the apps each holds; drawer folders and the apps
// in them; and categories every app of the drawer is sorted into.
internal object DrawerGroupTables {

    val TABLES = GroupsAndApps.TABLES + DrawerFolders.TABLES + Categories.TABLES

    // Null where the backup holds none of these tables.
    fun read(contents: BackupContents): List<ForeignGroup>? {
        val tables = contents.tables
        return when {
            GroupsAndApps.TABLES.all { it in tables } -> GroupsAndApps.read(tables)
            DrawerFolders.TABLES.all { it in tables } -> DrawerFolders.read(tables)
            Categories.TABLES.all { it in tables } -> Categories.read(tables)
            else -> null
        }
    }

    // "package/class#user", the user a number that may be missing.
    fun app(key: String?): ForeignApp? {
        val flat = key?.substringBeforeLast('#')
        val user = key?.takeIf { '#' in it }?.substringAfterLast('#')?.toLongOrNull()
        return IntentText.component(flat)?.let { (pkg, cls) -> ForeignApp(pkg, cls, BackupLayouts.userSerial(user)) }
    }
}

// Groups that are each a tab or a drawer folder, and a row per app a group
// holds. A tab can hold a drawer folder: its row names the folder by id in
// place of an activity. A group switched off keeps its row, flagged, and
// shows nowhere.
private object GroupsAndApps {
    const val GROUPS = "drawer_groups"
    const val MEMBERS = "appgroups"
    val TABLES = listOf(GROUPS, MEMBERS)

    private const val TAB = "TAB_APP_GROUP"
    private const val FOLDER = "FOLDER_APP_GROUP"
    private const val HELD_FOLDER = "FOLDER:"
    private const val SWITCHED_OFF = 128L

    fun read(tables: Map<String, List<Row>>): List<ForeignGroup> {
        val members = tables[MEMBERS].orEmpty().groupBy { it.long("groupId") }
        return tables[GROUPS].orEmpty().filterNot(::switchedOff).mapNotNull { row ->
            val id = row.long("_id")
            val type = row.text("groupType")
            if (id == null || (type != TAB && type != FOLDER)) return@mapNotNull null
            val keys = members[id].orEmpty().mapNotNull { it.text("component") }
            ForeignGroup(
                id = id,
                title = row.text("title").orEmpty(),
                folder = type == FOLDER,
                order = row.int("tabOrder") ?: 0,
                // Its apps leave the main list, or stay there too.
                keepInMain = row.long("hideApps")?.let { it == 0L },
                apps = keys.filterNot(::isFolder).mapNotNull(DrawerGroupTables::app),
                folders = keys.mapNotNull(::heldFolder),
                icon = row.blob("icon")
            )
        }
    }

    private fun switchedOff(row: Row): Boolean = ((row.long("flags") ?: 0L) and SWITCHED_OFF) != 0L

    private fun isFolder(key: String): Boolean = heldFolder(key) != null

    // The folder id in "package/FOLDER:id".
    private fun heldFolder(key: String): Long? =
        key.substringAfter('/', "").takeIf { it.startsWith(HELD_FOLDER) }
            ?.removePrefix(HELD_FOLDER)?.substringBefore('#')?.toLongOrNull()
}

// Drawer folders, and a row per app in one, each in its place.
private object DrawerFolders {
    const val FOLDERS = "folders"
    const val ITEMS = "folderitems"
    val TABLES = listOf(FOLDERS, ITEMS)

    fun read(tables: Map<String, List<Row>>): List<ForeignGroup> {
        val items = tables[ITEMS].orEmpty().groupBy { it.long("folderId") }
        return tables[FOLDERS].orEmpty().mapNotNull { row ->
            row.long("id")?.let { id ->
                ForeignGroup(
                    id = id,
                    title = row.text("title").orEmpty(),
                    folder = true,
                    order = row.int("rank") ?: 0,
                    // Hidden: its apps are out of the main list.
                    keepInMain = if (row.long("hide") == 1L) false else null,
                    apps = items[id].orEmpty().sortedBy { it.int("rank") ?: 0 }
                        .mapNotNull { DrawerGroupTables.app(it.text("item_info")) }
                )
            }
        }
    }
}

// Categories the drawer sorts every app into, each shown as a tab. The
// launcher's own categories have no label stored, only their id.
private object Categories {
    const val TABLE = "category"
    const val APPS = "draweritem"
    val TABLES = listOf(TABLE, APPS)

    private val NAMES = mapOf(
        "phone" to "Contacts",
        "internet" to "Internet",
        "games" to "Games",
        "media" to "Media",
        "utility" to "Utilities",
        "settings" to "Settings"
    )

    fun read(tables: Map<String, List<Row>>): List<ForeignGroup> {
        // Apps only: an app's shortcut or anything inside another item is
        // no member of a category's tab.
        val apps = tables[APPS].orEmpty()
            .filter { it.long("parentId") == null && it.text("deepShortcutId").isNullOrEmpty() }
            .groupBy { it.text("categoryId") }
        // A category the launcher made that no app is sorted into would be
        // an empty tab.
        return tables[TABLE].orEmpty()
            .filter { it.long("enabled") != 0L && (it.text("id") in apps || !it.text("customLabel").isNullOrBlank()) }
            .mapIndexedNotNull { index, row ->
                row.text("id")?.let { id ->
                    ForeignGroup(
                        id = index.toLong(),
                        title = row.text("customLabel")?.takeIf { it.isNotBlank() } ?: name(id),
                        folder = false,
                        order = row.int("position") ?: index,
                        // Every app is in some category, so the main list
                        // keeps them all.
                        keepInMain = true,
                        apps = apps[id].orEmpty().mapNotNull(::app)
                    )
                }
            }
    }

    private fun app(row: Row): ForeignApp? = row.text("packageName")?.takeIf { it.isNotEmpty() }?.let {
        val activity = row.text("activityName")?.takeIf(String::isNotEmpty)
        ForeignApp(it, activity, BackupLayouts.userSerial(row.long("userId")))
    }

    private fun name(id: String): String = NAMES[id] ?: id.replaceFirstChar { it.uppercaseChar() }
}
