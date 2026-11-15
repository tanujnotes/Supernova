package com.supernova.launcher.data.imports

// A home screen as another launcher's backup describes it, before any of it
// is turned into Supernova's own items: positions are that launcher's cells
// (fractions where it places on half cells), ids are its row ids.

internal enum class ForeignKind {
    APP,
    // An intent the launcher was handed or made: a legacy shortcut, an
    // activity, one of its own actions.
    INTENT,
    // An app shortcut, named by its id inside the owning package.
    APP_SHORTCUT,
    FOLDER,
    WIDGET
}

internal enum class ForeignPlace {
    DESKTOP,
    DOCK,
    FOLDER
}

internal data class ForeignItem(
    val id: Long,
    val kind: ForeignKind,
    val place: ForeignPlace,
    // The folder holding it, for ForeignPlace.FOLDER.
    val parentId: Long? = null,
    // Desktop page, or dock page where the launcher pages its dock.
    val page: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val spanX: Float = 1f,
    val spanY: Float = 1f,
    // Order inside a folder.
    val rank: Int = 0,
    val title: String = "",
    val intent: IntentText? = null,
    // For ForeignKind.APP_SHORTCUT: the shortcut's id inside its package.
    val shortcutId: String? = null,
    // "package/class" of the widget's provider.
    val widgetProvider: String? = null,
    // 0 is the phone's owner; a work profile carries its serial number.
    val userSerial: Long = 0L,
    // The icon the launcher kept for it, as an image file's bytes.
    val icon: ByteArray? = null
)

// What the backup says about sizes; null where it says nothing, which keeps
// the setting Supernova has.
internal data class ForeignGrid(
    val cols: Int? = null,
    val rows: Int? = null,
    val dockSlots: Int? = null,
    val pageCount: Int? = null,
    val drawerCols: Int? = null,
    val drawerRows: Int? = null,
    val folderCols: Int? = null,
    // Icons sit on half cells.
    val subgrid: Boolean = false
)

// An app a drawer group holds; the activity may be unnamed.
internal data class ForeignApp(val packageName: String, val className: String?, val userSerial: Long = 0L)

// One of the launcher's own groupings of its app drawer: a tab (or a
// category) the drawer can be filtered to, or a folder in the drawer's list.
internal data class ForeignGroup(
    val id: Long,
    val title: String,
    val folder: Boolean,
    // Place among the launcher's tabs, or among its folders.
    val order: Int,
    // Whether the main list keeps the group's apps too; null where the
    // launcher has no such choice.
    val keepInMain: Boolean? = null,
    val apps: List<ForeignApp> = emptyList(),
    // Ids of the drawer folders a tab holds.
    val folders: List<Long> = emptyList(),
    // A folder's own icon, as an image file's bytes.
    val icon: ByteArray? = null
)

internal data class ForeignLayout(
    val items: List<ForeignItem>,
    val grid: ForeignGrid,
    // Page numbers are the launcher's own and count empty pages; off, they
    // are ids that only order the pages.
    val literalPages: Boolean = true,
    // The page the home button lands on, in the launcher's numbering.
    val homePage: Int = 0,
    // Rows the backup holds that are nothing Supernova knows.
    val unread: Int = 0,
    // The app drawer's tabs and folders; null where the backup holds no
    // table of them.
    val groups: List<ForeignGroup>? = null
)
