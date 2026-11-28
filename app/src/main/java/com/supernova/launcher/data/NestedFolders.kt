package com.supernova.launcher.data

// Home and dock folders inside folders (#369). A folder holds the items whose
// folderId names it, and a folder among those holds its own in turn, to any
// depth. A folder never ends up inside itself: canHold refuses that move.
object NestedFolders {

    // The folder's own items, its first level.
    fun members(items: List<LauncherItem>, folderId: String): List<LauncherItem> =
        items.filter { it.container == ContainerType.FOLDER && it.folderId == folderId }

    // Everything inside the folder at any depth, nearest level first. A loop
    // in a damaged model stops at the first item seen twice.
    fun contents(items: List<LauncherItem>, folderId: String): List<LauncherItem> {
        val byFolder = items.filter { it.container == ContainerType.FOLDER && it.folderId != null }
            .groupBy { it.folderId }
        val out = ArrayList<LauncherItem>()
        val seen = hashSetOf(folderId)
        val queue = ArrayDeque(listOf(folderId))
        while (queue.isNotEmpty()) {
            val fresh = byFolder[queue.removeFirst()].orEmpty().filter { seen.add(it.id) }
            out += fresh
            fresh.filter { it.itemType == ItemType.FOLDER }.forEach { queue += it.id }
        }
        return out
    }

    // The items inside the folder that aren't folders: what its badge sums.
    fun leaves(items: List<LauncherItem>, folderId: String): List<LauncherItem> =
        contents(items, folderId).filter { it.itemType != ItemType.FOLDER }

    // Whether `moving` may go into folder `folderId`: an app or a shortcut
    // always, a folder when it isn't that folder and doesn't hold it.
    fun canHold(items: List<LauncherItem>, folderId: String, moving: LauncherItem): Boolean = when (moving.itemType) {
        ItemType.APP, ItemType.SHORTCUT -> true
        ItemType.FOLDER -> moving.id != folderId && contents(items, moving.id).none { it.id == folderId }
        ItemType.WIDGET -> false
    }

    // The folders an item sits in, innermost first.
    fun holders(items: List<LauncherItem>, itemId: String): List<LauncherItem> {
        val byId = items.associateBy { it.id }
        val out = ArrayList<LauncherItem>()
        var holder = byId[itemId]?.takeIf { it.container == ContainerType.FOLDER }?.folderId?.let(byId::get)
        while (holder != null && out.none { it.id == holder.id }) {
            out += holder
            holder = holder.takeIf { it.container == ContainerType.FOLDER }?.folderId?.let(byId::get)
        }
        return out
    }

    // Folders with nothing in them go, and the folders that held them may
    // then go too, until none is left empty. Nothing else changes.
    fun withEmptyFoldersRemoved(items: List<LauncherItem>): List<LauncherItem> {
        var kept = items
        while (true) {
            val held = kept.mapNotNullTo(HashSet()) { item ->
                item.folderId.takeIf { item.container == ContainerType.FOLDER }
            }
            val empty = kept.filter { it.itemType == ItemType.FOLDER && it.id !in held }.mapTo(HashSet()) { it.id }
            if (empty.isEmpty()) return kept
            kept = kept.filterNot { it.id in empty }
        }
    }
}
