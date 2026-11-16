package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.Toast
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.GroupPick
import com.supernova.launcher.data.LaunchableApp

// A drawer group's Select apps, from the Drawer groups page and from a
// drawer folder's menu: the given apps and, for a tab, the drawer folders it
// can hold, first and with their folder icons. Save writes only the ticks
// that changed, to the group stored under [name] (a rename can just have
// changed it).
class GroupChecklist(
    private val context: Context,
    private val viewModel: LauncherViewModel,
    private val icons: ItemIcons
) {
    // The group's apps (and, for a tab, its folders) to tick, under its name,
    // which can be typed over: Save renames it first, then stores the ticks
    // under the name it has now. onRenamed hears the new name.
    fun show(
        group: DrawerGroup,
        apps: List<LaunchableApp>,
        name: String = group.name,
        onRenamed: (String) -> Unit = {}
    ) {
        val picks = viewModel.groupPicks(group, apps)
        if (picks.isEmpty()) return
        val checked = picks.map { viewModel.isPicked(it, group) }.toBooleanArray()
        val folderIcons = HashMap<String, Drawable>()
        val iconOf: (GroupPick) -> Drawable? = { pick ->
            when (pick) {
                is GroupPick.App -> viewModel.appCatalog().iconFor(pick.app)
                is GroupPick.Folder -> folderIcons.getOrPut(pick.group.name) {
                    icons.drawerFolderIcon(pick.group, viewModel.drawerFolderApps(pick.group))
                }
            }
        }
        AppChecklist(context, picks, checked.copyOf(), iconOf, GroupPick::label).showRenamable(name) { ticks, typed ->
            // As stored now: a rename elsewhere may have come after the copy
            // this was opened with.
            val stored = viewModel.drawerGroups().firstOrNull { it.name == name } ?: group.copy(name = name)
            val renamed = viewModel.renameGroup(stored, typed)
            if (renamed == null) Toast.makeText(context, viewModel.nameTakenMessage(typed), Toast.LENGTH_LONG).show()
            val now = renamed ?: name
            if (now != name) onRenamed(now)
            val changes = picks.indices.filter { ticks[it] != checked[it] }.associate { picks[it] to ticks[it] }
            if (changes.isNotEmpty()) viewModel.setGroupMembership(now, changes)
        }
    }
}
