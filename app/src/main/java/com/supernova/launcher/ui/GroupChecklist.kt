package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.GroupPick
import com.supernova.launcher.data.LaunchableApp

// A drawer group's Select apps, from the Drawer groups page and from a
// drawer folder's menu: the given apps and, for a tab, the drawer folders it
// can hold, first and with their folder icons. Its title is the group's name,
// which can be typed over. Save renames the group and writes only the ticks
// that changed, both to the group by its id.
class GroupChecklist(
    private val context: Context,
    private val viewModel: LauncherViewModel,
    private val icons: ItemIcons
) {
    // name: the title to show, when a rename just typed may not be stored yet.
    fun show(group: DrawerGroup, apps: List<LaunchableApp>, name: String? = null) {
        val picks = viewModel.groupPicks(group, apps)
        if (picks.isEmpty()) return
        val checked = picks.map { viewModel.isPicked(it, group) }.toBooleanArray()
        val folderIcons = HashMap<String, Drawable>()
        val iconOf: (GroupPick) -> Drawable? = { pick ->
            when (pick) {
                is GroupPick.App -> viewModel.appCatalog().iconFor(pick.app)
                is GroupPick.Folder -> folderIcons.getOrPut(pick.group.id) {
                    icons.drawerFolderIcon(pick.group, viewModel.drawerFolderApps(pick.group))
                }
            }
        }
        // As stored now: a rename elsewhere may have come after the copy this
        // was opened with.
        val title = name ?: viewModel.drawerGroups().firstOrNull { it.id == group.id }?.name ?: group.name
        AppChecklist(context, picks, checked.copyOf(), iconOf, GroupPick::label).showRenamable(title) { ticks, typed ->
            val stored = viewModel.drawerGroups().firstOrNull { it.id == group.id } ?: group
            viewModel.renameGroup(stored, typed)
            val changes = picks.indices.filter { ticks[it] != checked[it] }.associate { picks[it] to ticks[it] }
            if (changes.isNotEmpty()) viewModel.setGroupMembership(group.id, changes)
        }
    }
}
