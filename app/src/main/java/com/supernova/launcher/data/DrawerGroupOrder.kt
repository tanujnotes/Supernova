package com.supernova.launcher.data

// The Drawer groups page's edits of the stored list as a whole: a drag that
// reorders the tabs or the folders (or carries a group from one to the
// other), and a deleted group brought back.
object DrawerGroupOrder {

    // The order every surface shows groups in: by their stored order, then
    // by name.
    fun sorted(groups: List<DrawerGroup>): List<DrawerGroup> =
        groups.sortedWith(compareBy<DrawerGroup> { it.order }.then(LabelOrder.by { it.name }))

    // The groups of one type in the order ids gives, numbered again from
    // the top. Tabs and folders are separate lists (the tab bar, the drawer's
    // folders), so the other type's groups keep their places among them. A
    // group of the type that ids leaves out (one made meanwhile) follows
    // the named ones.
    fun reordered(groups: List<DrawerGroup>, type: DrawerGroupType, ids: List<String>): List<DrawerGroup> {
        val sorted = sorted(groups)
        val ofType = sorted.filter { it.type == type }
        val named = ids.distinct().mapNotNull { id -> ofType.firstOrNull { it.id == id } }
        val queue = ArrayDeque(named + ofType.filterNot { it in named })
        return sorted
            .map { if (it.type == type) queue.removeFirst() else it }
            .mapIndexed { position, group -> group.copy(order = position) }
    }

    // A drag across the Drawer groups page's two cards (#267): the tabs and
    // the folders each in the order their ids give, a group dragged into the
    // other card changed by retype on the way (LauncherViewModel.retyped).
    fun arranged(
        groups: List<DrawerGroup>,
        tabs: List<String>,
        folders: List<String>,
        retype: (DrawerGroup, DrawerGroupType) -> DrawerGroup
    ): List<DrawerGroup> {
        val retyped = groups.map {
            when {
                it.id in tabs && it.type != DrawerGroupType.TAB -> retype(it, DrawerGroupType.TAB)
                it.id in folders && it.type != DrawerGroupType.FOLDER -> retype(it, DrawerGroupType.FOLDER)
                else -> it
            }
        }
        return reordered(reordered(retyped, DrawerGroupType.TAB, tabs), DrawerGroupType.FOLDER, folders)
    }

    // Undo of such a drag: every group still here back to its type, order
    // and choices in before. Apps and names picked since stay.
    fun reverted(now: List<DrawerGroup>, before: List<DrawerGroup>): List<DrawerGroup> =
        now.map { group ->
            before.firstOrNull { it.id == group.id }
                ?.let { group.copy(type = it.type, order = it.order, smart = it.smart, keepInMain = it.keepInMain) }
                ?: group
        }

    // A deleted group put back: the group as it was in before, and its place
    // in every tab that held it then and still exists. Everything stored
    // since stays. The user asked for it back, so it is theirs: a smart
    // folder's marker goes, or the next regeneration would drop it again.
    // Unchanged when before has no such group, or one with its id exists now.
    fun restored(now: List<DrawerGroup>, before: List<DrawerGroup>, id: String): List<DrawerGroup> {
        val group = before.firstOrNull { it.id == id }
        if (group == null || now.any { it.id == id }) return now
        val key = TabFolders.keyOf(id)
        val holders = before.filter { key in it.members }.map { it.id }.toSet()
        return now.map { if (it.id in holders) it.copy(members = it.members + key) else it } +
            group.copy(smart = false)
    }
}
