package com.supernova.launcher.data

// A folder's order. Sort: its members re-ranked by label, the key the A-Z
// folder setting sorts by. Apps with the same label keep their order.
// Reordered: an app dragged to a new place in the open folder. Either way
// nothing else in the model moves.
object FolderSort {

    fun sorted(current: LauncherModel, folderId: String): LauncherModel =
        ranked(current, LabelOrder.sorted(members(current, folderId)) { it.label })

    // The members in the order `orderedIds` lists them. One the list doesn't
    // name (added while the folder was open) follows, in its old order.
    fun reordered(current: LauncherModel, folderId: String, orderedIds: List<String>): LauncherModel {
        val place = orderedIds.withIndex().associate { (index, id) -> id to index }
        return ranked(current, members(current, folderId).sortedBy { place[it.id] ?: Int.MAX_VALUE })
    }

    private fun members(current: LauncherModel, folderId: String): List<LauncherItem> =
        current.items
            .filter { it.container == ContainerType.FOLDER && it.folderId == folderId }
            .sortedBy { it.rank }

    // Ranks 0.. in the given order; the model itself when nothing changes.
    private fun ranked(current: LauncherModel, order: List<LauncherItem>): LauncherModel {
        val ranks = order.mapIndexed { index, item -> item.id to index }.toMap()
        if (current.items.all { item -> ranks[item.id]?.let { it == item.rank } ?: true }) return current
        return current.copy(
            items = current.items.map { item -> ranks[item.id]?.let { item.copy(rank = it) } ?: item }
        )
    }
}
