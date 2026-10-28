package com.supernova.launcher.data

// A drop on the middle of a dock icon: the dropped app joins the folder in
// that slot, or the two apps become a new folder there.
internal object DockFolders {

    private fun foldable(item: LauncherItem) = item.itemType == ItemType.APP || item.itemType == ItemType.SHORTCUT

    // The dock item the drop goes into a folder with, or null when it can't:
    // a folder goes into no folder, and an app already in that folder stays.
    fun target(current: LauncherModel, moving: LauncherItem, rank: Int): LauncherItem? =
        current.items.firstOrNull { it.container == ContainerType.DOCK && it.rank == rank && it.id != moving.id }
            ?.takeIf {
                foldable(moving) && (it.itemType == ItemType.FOLDER || foldable(it)) && moving.folderId != it.id
            }

    // folderId names the folder it makes, if it makes one. An item new to
    // the layout (from the drawer) comes in with its own id.
    fun merged(current: LauncherModel, moving: LauncherItem, rank: Int, folderId: String): LauncherModel {
        val target = target(current, moving, rank) ?: return current
        val intoExisting = target.itemType == ItemType.FOLDER
        val joining = if (intoExisting) target.id else folderId
        val nextRank = if (intoExisting) {
            (current.items.filter { it.folderId == target.id }.maxOfOrNull { it.rank } ?: -1) + 1
        } else {
            1
        }
        fun intoFolder(item: LauncherItem, rank: Int) = item.copy(
            container = ContainerType.FOLDER,
            folderId = joining,
            workspaceX = null,
            workspaceY = null,
            rank = rank
        )
        val isNew = current.items.none { it.id == moving.id }
        var items = current.items.map {
            when {
                it.id == moving.id -> intoFolder(it, nextRank)
                it.id == target.id && !intoExisting -> intoFolder(it, 0)
                else -> it
            }
        }
        if (isNew) items = items + intoFolder(moving, nextRank)
        // The new folder takes the slot the icon had.
        if (!intoExisting) {
            items = items + LauncherItem(
                id = folderId,
                itemType = ItemType.FOLDER,
                container = ContainerType.DOCK,
                rank = rank,
                label = "Folder"
            )
        }
        val next = current.copy(items = items)
        // An app taken out of a folder may leave too little of it to stay
        // a folder.
        return if (moving.container == ContainerType.FOLDER) {
            LauncherRepository.withSparseFolderDissolved(next, moving.folderId)
        } else {
            next
        }
    }
}
