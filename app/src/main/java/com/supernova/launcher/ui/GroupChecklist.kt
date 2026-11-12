package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.Drawable
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
    fun show(group: DrawerGroup, apps: List<LaunchableApp>, name: String = group.name) {
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
        AppChecklist(context, picks, checked.copyOf(), iconOf, GroupPick::label).show(name) { ticks ->
            val changes = picks.indices.filter { ticks[it] != checked[it] }.associate { picks[it] to ticks[it] }
            if (changes.isNotEmpty()) viewModel.setGroupMembership(name, changes)
        }
    }
}
