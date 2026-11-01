package com.supernova.launcher.data

// Select apps' result as one model change: the ticked apps come in as new
// items, the unticked ones go, and a folder left too small gives way. Each added
// app comes with its new item's id.
internal object FolderChecklist {

    fun applied(
        current: LauncherModel,
        folderId: String,
        added: List<Pair<LaunchableApp, String>>,
        removedIds: Set<String>
    ): LauncherModel {
        if (current.items.none { it.id == folderId && it.itemType == ItemType.FOLDER }) return current
        val kept = current.items.filterNot { it.folderId == folderId && it.id in removedIds }
        val firstRank = (kept.filter { it.folderId == folderId }.maxOfOrNull { it.rank } ?: -1) + 1
        val newItems = added.mapIndexed { index, (app, newId) ->
            LauncherItem(
                id = newId,
                itemType = ItemType.APP,
                container = ContainerType.FOLDER,
                folderId = folderId,
                rank = firstRank + index,
                label = app.label,
                packageName = app.packageName,
                className = app.className,
                userSerial = app.userSerial
            )
        }
        return LauncherRepository.withSparseFolderDissolved(current.copy(items = kept + newItems), folderId)
    }
}
