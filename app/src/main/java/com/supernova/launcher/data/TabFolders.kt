package com.supernova.launcher.data

// Drawer folders inside drawer tabs. A tab holds a folder the way it holds
// an app: by a key among its members. An app's key is "serial/package/class";
// a folder's is "folder:" and its name, which is the folder's identity in
// storage. Members are stored comma-separated, so the name's ',' (and the '%'
// that escapes it) are escaped; a name never holds a '|'.
//
// A tab shows the folders it holds. The main (All or Personal) list shows
// every other folder, and a held one too while the tab keeps its apps in the
// main tab: the folder's key leaves the main list as an app's does.
object TabFolders {
    private const val PREFIX = "folder:"
    private const val ESCAPED_PERCENT = "%25"
    private const val ESCAPED_COMMA = "%2C"

    fun keyOf(folderName: String): String =
        PREFIX + folderName.replace("%", ESCAPED_PERCENT).replace(",", ESCAPED_COMMA)

    // The folder name a member key names, or null for an app's key.
    fun nameOf(key: String): String? {
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
        folders.filter { keyOf(it.name) in tab.members }

    // The folders the main list shows: all but those a tab took out of it
    // (isolated = the member keys of every tab that takes its apps out).
    fun onMainList(folders: List<DrawerGroup>, isolated: Set<String>): List<DrawerGroup> =
        if (isolated.isEmpty()) folders else folders.filterNot { keyOf(it.name) in isolated }

    // A group renamed: every tab that held it under the old name holds it
    // under the new one. Any group's, not only a folder's: a folder turned
    // into a tab keeps its place in other tabs for when it turns back, and
    // that key must follow its name too.
    fun renamed(groups: List<DrawerGroup>, oldName: String, newName: String): List<DrawerGroup> {
        val old = keyOf(oldName)
        val new = keyOf(newName)
        return groups.map { group ->
            if (old in group.members) group.copy(members = group.members - old + new) else group
        }
    }

    // A group deleted: no tab holds it any more, so a later folder of the
    // same name doesn't turn up in its tabs.
    fun forgotten(groups: List<DrawerGroup>, name: String): List<DrawerGroup> {
        val key = keyOf(name)
        return groups.map { group -> if (key in group.members) group.copy(members = group.members - key) else group }
    }
}

// A row in a drawer group's Select apps: an app, or a drawer folder a tab
// can hold.
sealed interface GroupPick {
    val label: String

    data class App(val app: LaunchableApp) : GroupPick {
        override val label: String get() = app.label
    }

    data class Folder(val group: DrawerGroup) : GroupPick {
        override val label: String get() = group.name
    }
}
