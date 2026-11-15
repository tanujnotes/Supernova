package com.supernova.launcher.data

// A home or dock folder moved into the drawer, as a drawer folder (on the
// main list, or held by a tab) or as a tab of its own. Its apps become the
// group's members; anything a drawer group can't hold (shortcuts, contacts,
// activities, launcher actions) stays behind on the home screen, in the
// folder while two or more are left, else in its place.
object FolderToDrawer {
    private const val FALLBACK_NAME = "Folder"

    // The folder's apps a drawer group can hold: launchable apps, keyed as
    // the drawer keys them ("serial/package/class").
    fun movable(members: List<LauncherItem>): List<LauncherItem> =
        members.filter { it.itemType == ItemType.APP && it.packageName != null && it.className != null }

    fun memberKey(item: LauncherItem): String = "${item.userSerial}/${item.packageName}/${item.className}"

    // A group's name is its identity, never blank and never a '|' (the
    // storage separator): the folder's own name, numbered past any group
    // that has it already.
    fun uniqueName(label: String, taken: Collection<String>): String {
        val base = label.trim().replace('|', '/').ifEmpty { FALLBACK_NAME }
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in taken }
    }

    // The stored groups with the moved folder added: a drawer folder keeps
    // the folder's icon, and the tab it was put in holds it.
    fun withGroupAdded(
        groups: List<DrawerGroup>,
        folder: LauncherItem,
        members: List<LauncherItem>,
        name: String,
        target: DrawerTarget
    ): List<DrawerGroup> {
        val type = if (target == DrawerTarget.OwnTab) DrawerGroupType.TAB else DrawerGroupType.FOLDER
        val asFolder = type == DrawerGroupType.FOLDER
        val packIcon = asFolder && folder.customIconPack != null && folder.customIconName != null
        val group = DrawerGroup(
            name = name,
            members = movable(members).mapTo(linkedSetOf(), ::memberKey),
            type = type,
            // As New tab and New folder start: the apps leave the main list.
            keepInMain = false,
            iconPack = folder.customIconPack.takeIf { packIcon },
            iconName = folder.customIconName.takeIf { packIcon },
            iconBase64 = folder.iconBase64.takeIf { asFolder && !packIcon },
            iconRaw = asFolder && folder.iconRaw
        )
        val holder = (target as? DrawerTarget.InTab)?.tab
        val held = groups.map { other ->
            if (other.name == holder && other.type == DrawerGroupType.TAB) {
                other.copy(members = other.members + TabFolders.keyOf(name))
            } else {
                other
            }
        }
        return held + group
    }

    // The home screen after the move: the moved apps go, and the folder with
    // them unless something stayed behind - then it keeps what stayed, or
    // gives way to it when only one is left.
    fun homeAfterMove(current: LauncherModel, folderId: String): LauncherModel {
        val members = current.items.filter { it.container == ContainerType.FOLDER && it.folderId == folderId }
        val moved = movable(members).mapTo(mutableSetOf()) { it.id }
        if (moved.isEmpty()) return current
        val left = members.size - moved.size
        val gone = if (left == 0) moved + folderId else moved
        val next = current.copy(items = current.items.filterNot { it.id in gone })
        return if (left == 0) next else LauncherRepository.withSparseFolderDissolved(next, folderId)
    }
}

// Where a moved folder goes: a drawer folder on the main (All) list or held
// by a tab, or a tab of its own.
sealed interface DrawerTarget {
    data object MainList : DrawerTarget

    data class InTab(val tab: String) : DrawerTarget

    data object OwnTab : DrawerTarget
}

// One move, for its Undo: the folder and its members as they were, how many
// of them stayed on the home screen, and the name the group was stored
// under (set once the drawer edit ran).
class FolderMove(val items: List<LauncherItem>, val leftBehind: Int) {
    @Volatile
    var groupName: String? = null
}
