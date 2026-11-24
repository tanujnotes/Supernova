package com.supernova.launcher.data

// A home item's removal and the Undo that takes it back, pure for tests.
object ItemRemoval {

    // A folder goes with its apps. An app removed from a folder can leave it
    // with one app, which then takes the folder's place (#345); the Undo of
    // that brings the folder back through replaced().
    fun removed(current: LauncherModel, itemId: String): LauncherModel {
        val removedItem = current.items.firstOrNull { it.id == itemId }
        val removeIds = mutableSetOf(itemId)
        if (removedItem?.itemType == ItemType.FOLDER) {
            current.items.filter { it.folderId == itemId }.forEach { removeIds.add(it.id) }
        }
        val next = current.copy(items = current.items.filterNot { it.id in removeIds })
        return if (removedItem?.container == ContainerType.FOLDER) {
            LauncherRepository.withSparseFolderDissolved(next, removedItem.folderId)
        } else {
            next
        }
    }

    // The captured items replace whatever now has their ids, then go back
    // as LauncherRepository.restored() puts them.
    fun replaced(
        current: LauncherModel,
        items: List<LauncherItem>,
        cols: Int,
        rows: Int,
        dockSlots: Int
    ): LauncherModel {
        val ids = items.mapTo(mutableSetOf()) { it.id }
        val without = current.copy(items = current.items.filterNot { it.id in ids })
        return LauncherRepository.restored(without, items, cols, rows, dockSlots)
    }
}
