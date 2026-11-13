package com.supernova.launcher.ui.settings.groups

import androidx.annotation.StringRes
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.LaunchableApp

// One line of the Drawer groups page's list. Each keeps one key for as long
// as it is on the page, so a change moves and rebinds rows rather than
// redrawing the list.
sealed class GroupListRow {
    abstract val key: String

    // The drawer's tab bar as the groups make it: the built-in tabs (All,
    // Work), then the groups', with the tab the drawer opens on picked out
    // over a few of its apps. barHidden: the bar is off, and says so instead.
    data class Preview(
        val builtIn: List<String>,
        val tabs: List<String>,
        val defaultTab: String,
        val apps: List<LaunchableApp>,
        val barHidden: Boolean
    ) : GroupListRow() {
        override val key: String get() = "preview"

        val labels: List<String> get() = builtIn + tabs

        // The default tab, else the first, as the drawer picks.
        val selected: Int get() = tabs.indexOf(defaultTab).let { if (it < 0) 0 else it + builtIn.size }
    }

    data class Header(@StringRes val title: Int) : GroupListRow() {
        override val key: String get() = "header:$title"
    }

    // A tab or a folder: its apps (a tab's first few are its picture), the
    // drawer folders a tab holds, whether its apps stay in the main tab too,
    // and whether the drawer opens on it.
    data class Group(
        val group: DrawerGroup,
        val members: List<LaunchableApp>,
        val folders: Int,
        val kept: Boolean,
        val isDefault: Boolean
    ) : GroupListRow() {
        override val key: String get() = "group:${group.name}"
    }

    // New tab / New folder, the last row of its card.
    data class Add(val type: DrawerGroupType) : GroupListRow() {
        override val key: String get() = "add:$type"
    }

    // A line of help: under a card, or opening the empty page.
    data class Caption(@StringRes val text: Int) : GroupListRow() {
        override val key: String get() = "caption:$text"
    }

    // The empty page's card for one kind of group: what it is, and its
    // New button.
    data class Empty(val type: DrawerGroupType) : GroupListRow() {
        override val key: String get() = "empty:$type"
    }
}
