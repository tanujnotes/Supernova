package com.supernova.launcher.data

// Drawer folders inside drawer tabs. A tab holds a folder the way it holds
// an app: by a key among its members. An app's key is "serial/package/class";
// a folder's is "folder:" and its id (DrawerGroup.id: the name, for a group
// from before ids). Members are stored comma-separated, so the id's ',' (and
// the '%' that escapes it) are escaped; an id never holds a '|'.
//
// A tab shows the folders it holds. The main (All or Personal) list shows
// every other folder, and a held one too while the tab keeps its apps in the
// main tab: the folder's key leaves the main list as an app's does. A drawer
// folder holds folders the same way (#229), shown in its card instead.
object TabFolders {
    private const val PREFIX = "folder:"
    private const val ESCAPED_PERCENT = "%25"
    private const val ESCAPED_COMMA = "%2C"

    fun keyOf(id: String): String =
        PREFIX + id.replace("%", ESCAPED_PERCENT).replace(",", ESCAPED_COMMA)

    // The folder id a member key names, or null for an app's key.
    fun idOf(key: String): String? {
        if (!key.startsWith(PREFIX)) return null
        val escaped = key.substring(PREFIX.length)
        return buildString {
            var i = 0
            while (i < escaped.length) {
                when {
                    escaped.startsWith(ESCAPED_COMMA, i) -> {
                        append(',')
                        i += ESCAPED_COMMA.length
                    }
                    escaped.startsWith(ESCAPED_PERCENT, i) -> {
                        append('%')
                        i += ESCAPED_PERCENT.length
                    }
                    else -> {
                        append(escaped[i])
                        i++
                    }
                }
            }
        }
    }

    // The folders a tab shows, in the order given (the drawer's).
    fun inTab(tab: DrawerGroup, folders: List<DrawerGroup>): List<DrawerGroup> =
        folders.filter { keyOf(it.id) in tab.members }

    // The folders the main list shows: all but those a tab took out of it
    // (isolated = the member keys of every tab that takes its apps out).
    fun onMainList(folders: List<DrawerGroup>, isolated: Set<String>): List<DrawerGroup> =
        if (isolated.isEmpty()) folders else folders.filterNot { keyOf(it.id) in isolated }

    // The drawer folders a drawer folder holds (#229), in the order given;
    // never the folder itself.
    fun heldBy(folder: DrawerGroup, folders: List<DrawerGroup>): List<DrawerGroup> =
        folders.filter { it.id != folder.id && keyOf(it.id) in folder.members }

    // Every folder id inside a folder, however deep. Stops at one already
    // seen, so a loop a backup brought in can't run forever.
    fun nestedIds(folder: DrawerGroup, folders: List<DrawerGroup>): Set<String> {
        val seen = mutableSetOf<String>()
        val pending = ArrayDeque(heldBy(folder, folders))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next.id == folder.id || !seen.add(next.id)) continue
            pending.addAll(heldBy(next, folders))
        }
        return seen
    }

    // Whether a folder can go inside another: not into itself, and not into
    // one it already holds somewhere down, which would make a loop.
    fun canHold(parent: DrawerGroup, child: DrawerGroup, folders: List<DrawerGroup>): Boolean =
        parent.id != child.id && parent.id !in nestedIds(child, folders)

    // Whether a folder has something to show: an app (hasApps), or a folder
    // inside it that has one.
    fun hasContent(folder: DrawerGroup, folders: List<DrawerGroup>, hasApps: (DrawerGroup) -> Boolean): Boolean =
        hasApps(folder) || nestedIds(folder, folders).any { id -> folders.any { it.id == id && hasApps(it) } }

    // The keys of the folders that folders taking their contents out of the
    // main list hold: those folders show inside them, not on the list.
    fun nestedKeys(folders: List<DrawerGroup>, leavesMainList: (DrawerGroup) -> Boolean): Set<String> =
        folders.filter(leavesMainList).flatMapTo(mutableSetOf()) { parent ->
            heldBy(parent, folders).map { keyOf(it.id) }
        }

    // A group deleted: no tab holds it any more, so a later folder with the
    // same id (a group from before ids, put back) doesn't turn up in its tabs.
    fun forgotten(groups: List<DrawerGroup>, id: String): List<DrawerGroup> {
        val key = keyOf(id)
        return groups.map { group -> if (key in group.members) group.copy(members = group.members - key) else group }
    }
}

// Where a drawer folder can be put from its own page (#238): any tab, and
// any other folder that can hold it (TabFolders.canHold); tabs first, each
// kind in the order given.
object FolderPlaces {
    private const val STEP = " › "

    fun of(folder: DrawerGroup, groups: List<DrawerGroup>): List<DrawerGroup> {
        val folders = groups.filter { it.type == DrawerGroupType.FOLDER }
        return groups.filter { it.type == DrawerGroupType.TAB || TabFolders.canHold(it, folder, folders) }
            .sortedBy { it.type != DrawerGroupType.TAB }
    }

    // Where a folder sits (#285), so folders with the same name can be told
    // apart: one path per tab or folder holding it, outermost first ("Other",
    // "Other › Google"). A parent folder held in several places shows its
    // first; empty for a folder no tab or folder holds.
    fun paths(folder: DrawerGroup, groups: List<DrawerGroup>): List<String> =
        parents(folder, groups).map { pathTo(it, groups, mutableSetOf(folder.id)) }

    private fun parents(folder: DrawerGroup, groups: List<DrawerGroup>): List<DrawerGroup> {
        val key = TabFolders.keyOf(folder.id)
        return groups.filter { it.id != folder.id && key in it.members }
            .sortedBy { it.type != DrawerGroupType.TAB }
    }

    // A loop a backup brought in stops at a folder already on the path.
    private fun pathTo(group: DrawerGroup, groups: List<DrawerGroup>, seen: MutableSet<String>): String {
        val outer = if (group.type == DrawerGroupType.TAB || !seen.add(group.id)) {
            null
        } else {
            parents(group, groups).firstOrNull { it.id !in seen }
        }
        return if (outer == null) group.name else pathTo(outer, groups, seen) + STEP + group.name
    }
}

// A row in a drawer group's Select apps: an app, or a drawer folder a tab
// or another folder can hold. Also a drawer folder's contents, in its card.
sealed interface GroupPick {
    val label: String

    data class App(val app: LaunchableApp) : GroupPick {
        override val label: String get() = app.label
    }

    data class Folder(val group: DrawerGroup) : GroupPick {
        override val label: String get() = group.name
    }
}
