package com.supernova.launcher.data

// A folder's Sort: its members re-ranked by label, the key the A-Z folder
// setting sorts by. Apps with the same label keep their order, and nothing
// else in the model moves.
object FolderSort {

    fun sorted(current: LauncherModel, folderId: String): LauncherModel {
        val ranks = current.items
            .filter { it.container == ContainerType.FOLDER && it.folderId == folderId }
            .sortedBy { it.rank }
            .sortedBy { it.label.lowercase() }
            .mapIndexed { index, item -> item.id to index }
            .toMap()
        if (current.items.all { item -> ranks[item.id]?.let { it == item.rank } ?: true }) return current
        return current.copy(
            items = current.items.map { item -> ranks[item.id]?.let { item.copy(rank = it) } ?: item }
        )
    }
}
