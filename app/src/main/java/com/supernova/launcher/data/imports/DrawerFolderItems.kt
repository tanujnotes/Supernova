package com.supernova.launcher.data.imports

// A drawer folder put on the home screen or in the dock. The backup keeps it
// as a folder (or an icon) whose activity names the drawer folder in place of
// a class: "pkg/FOLDER:id", or "pkg/FOLDER:-2nn", the place its apps are kept
// in, for id nn. It comes over as Supernova places a drawer folder on home: a
// folder of its own, with the drawer folder's name, icon and apps. One whose
// drawer folder is gone is left out, not made an empty folder.
internal object DrawerFolderItems {

    private const val HELD_FOLDER = "FOLDER:"
    private const val PLACE_BASE = 200L

    fun expanded(layout: ForeignLayout): ForeignLayout {
        val folders = layout.groups.orEmpty().filter { it.folder }.associateBy { it.id }
        // Ids for the apps made here, clear of the backup's own.
        var nextId = (layout.items.maxOfOrNull { it.id } ?: 0L) + 1
        val gone = layout.items.count { item ->
            item.place != ForeignPlace.FOLDER && folderId(item)?.let { it !in folders } == true
        }
        val items = layout.items.flatMap { item ->
            val id = folderId(item)
            val group = id?.let(folders::get)
            if (id == null || item.place == ForeignPlace.FOLDER) {
                listOf(item)
            } else if (group == null) {
                emptyList()
            } else {
                val folder = item.copy(
                    kind = ForeignKind.FOLDER,
                    title = group.title.ifBlank { item.title },
                    intent = null,
                    icon = group.icon
                )
                listOf(folder) + group.apps.mapIndexed { rank, app -> member(app, folder.id, rank, nextId++) }
            }
        }
        return layout.copy(items = items, unread = layout.unread + gone)
    }

    // The drawer folder an item opens, by its id; null for anything else.
    private fun folderId(item: ForeignItem): Long? {
        val number = item.intent?.component?.second?.takeIf { it.startsWith(HELD_FOLDER) }
            ?.removePrefix(HELD_FOLDER)?.toLongOrNull()
        return if (number != null && number <= -PLACE_BASE) -number - PLACE_BASE else number
    }

    private fun member(app: ForeignApp, folderId: Long, rank: Int, id: Long) = ForeignItem(
        id = id,
        kind = ForeignKind.APP,
        place = ForeignPlace.FOLDER,
        parentId = folderId,
        rank = rank,
        intent = IntentText(
            raw = "",
            action = null,
            categories = emptyList(),
            packageName = app.packageName,
            component = app.className?.let { app.packageName to it },
            extras = emptyMap()
        ),
        userSerial = app.userSerial
    )
}
