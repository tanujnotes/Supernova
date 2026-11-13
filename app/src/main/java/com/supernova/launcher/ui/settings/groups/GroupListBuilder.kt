package com.supernova.launcher.ui.settings.groups

import android.content.Context
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.LabelOrder
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.data.TabFolders
import com.supernova.launcher.ui.DrawerTabBar
import com.supernova.launcher.ui.LauncherViewModel

// The Drawer groups page's list from what is stored: the tab bar preview,
// the Tabs card, the Folders card, each with its New row; or, with no group
// yet, what tabs and folders are.
class GroupListBuilder(private val context: Context, private val viewModel: LauncherViewModel) {

    fun rows(): List<GroupListRow> {
        val groups = viewModel.drawerGroups()
        if (groups.isEmpty()) {
            return listOf(
                GroupListRow.Caption(R.string.groups_intro),
                GroupListRow.Empty(DrawerGroupType.TAB),
                GroupListRow.Empty(DrawerGroupType.FOLDER)
            )
        }
        val apps = LabelOrder.sorted(viewModel.visibleApps()) { it.label }
        val tabs = groups.filter { it.type == DrawerGroupType.TAB }
        val folders = groups.filter { it.type == DrawerGroupType.FOLDER }
        val defaultTab = viewModel.state.value.prefs.drawerDefaultTab
        return buildList {
            if (tabs.isNotEmpty()) add(preview(tabs, apps))
            add(GroupListRow.Header(R.string.group_tabs_header))
            tabs.forEach { add(row(it, apps, TabFolders.inTab(it, folders).size, it.name == defaultTab)) }
            add(GroupListRow.Add(DrawerGroupType.TAB))
            if (tabs.size > 1) add(GroupListRow.Caption(R.string.group_tab_hint))
            add(GroupListRow.Header(R.string.group_folders_header))
            folders.forEach { add(row(it, apps, 0, false)) }
            add(GroupListRow.Add(DrawerGroupType.FOLDER))
            if (folders.isNotEmpty()) add(GroupListRow.Caption(R.string.group_folder_hint))
        }
    }

    // "3 tabs · 2 folders", the Drawer groups row's value on the App drawer
    // page; null with no group.
    fun summary(): CharSequence? {
        val groups = viewModel.drawerGroups()
        val tabs = groups.count { it.type == DrawerGroupType.TAB }
        val folders = groups.size - tabs
        val resources = context.resources
        return listOfNotNull(
            tabs.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.group_tabs_count, it, it) },
            folders.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.group_folders_count, it, it) }
        ).joinToString(SEPARATOR).ifEmpty { null }
    }

    private fun row(group: DrawerGroup, apps: List<LaunchableApp>, folders: Int, isDefault: Boolean) =
        GroupListRow.Group(
            group = group,
            members = apps.filter { viewModel.hiddenKey(it) in group.members },
            folders = folders,
            kept = !viewModel.leavesMainList(group),
            isDefault = isDefault
        )

    // The bar's tabs as the drawer builds them (DrawerController.rebuildTabs):
    // All unless it is hidden, Work while the work tab is on, then the groups.
    private fun preview(tabs: List<DrawerGroup>, apps: List<LaunchableApp>): GroupListRow.Preview {
        val prefs = viewModel.state.value.prefs
        val work = viewModel.workTabActive()
        val builtIn = buildList {
            if (!prefs.drawerHideAllTab) add(context.getString(R.string.drawer_all))
            if (work) add(context.getString(R.string.drawer_work))
        }
        val opensOn = tabs.firstOrNull { it.name == prefs.drawerDefaultTab }
            ?: tabs.first().takeIf { builtIn.isEmpty() }
        val shown = when {
            opensOn != null -> apps.filter { viewModel.hiddenKey(it) in opensOn.members }
            prefs.drawerHideAllTab -> apps.filter { it.userSerial != 0L }
            else -> mainList(apps, work)
        }
        return GroupListRow.Preview(
            builtIn = builtIn,
            tabs = tabs.map { it.name },
            defaultTab = prefs.drawerDefaultTab,
            apps = shown.take(PREVIEW_APPS),
            barHidden = prefs.drawerTabBar == DrawerTabBar.NONE
        )
    }

    // The All list: without the apps a tab took out of it, and without the
    // work apps while they have a tab of their own.
    private fun mainList(apps: List<LaunchableApp>, work: Boolean): List<LaunchableApp> {
        val isolated = viewModel.isolatedTabKeys()
        return apps.filter { viewModel.hiddenKey(it) !in isolated && !(work && it.userSerial != 0L) }
    }

    private companion object {
        const val PREVIEW_APPS = 5
        const val SEPARATOR = " · "
    }
}
