package com.supernova.launcher.ui

import android.content.Context
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.GroupPick
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.databinding.SheetAppEditBinding

// The Edit sheet's Drawer groups row for an app opened from the drawer: the
// drawer tabs and folders, ticked where the app is in one, so a long-press
// can put an app in a folder without going through the folder's Select
// apps (#169). The ticks are held until the sheet's Save, which writes only
// the groups that changed.
class DrawerGroupsPick(
    private val context: Context,
    private val viewModel: LauncherViewModel,
    private val app: LaunchableApp
) {
    private val groups = viewModel.drawerGroups()
    private val saved = groups.map { viewModel.isPicked(GroupPick.App(app), it) }.toBooleanArray()
    private val ticks = saved.copyOf()

    fun bind(binding: SheetAppEditBinding) {
        if (groups.isEmpty()) return
        binding.drawerGroupsRow.visibility = View.VISIBLE
        render(binding)
        binding.drawerGroupsRow.setOnClickListener {
            val labels = groups.map { group ->
                val type = context.getString(
                    if (group.type == DrawerGroupType.TAB) R.string.group_type_tab else R.string.folder
                )
                "${group.name} · $type"
            }.toTypedArray()
            val pending = ticks.copyOf()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.drawer_groups)
                .setMultiChoiceItems(labels, pending) { _, which, checked -> pending[which] = checked }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    pending.copyInto(ticks)
                    render(binding)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    companion object {
        // Only for an app opened from the drawer; a private space app has
        // no drawer groups.
        fun forSheet(
            context: Context,
            viewModel: LauncherViewModel,
            app: LaunchableApp,
            fromDrawer: Boolean,
            binding: SheetAppEditBinding
        ): DrawerGroupsPick? =
            if (!fromDrawer || app.privateSpace) null
            else DrawerGroupsPick(context, viewModel, app).also { it.bind(binding) }
    }

    fun save() {
        groups.indices.filter { ticks[it] != saved[it] }.forEach { i ->
            viewModel.setGroupMembership(groups[i].id, mapOf(GroupPick.App(app) to ticks[i]))
        }
    }

    private fun render(binding: SheetAppEditBinding) {
        val names = groups.indices.filter { ticks[it] }.map { groups[it].name }
        binding.drawerGroupsValue.text =
            if (names.isEmpty()) context.getString(R.string.option_none) else names.joinToString(", ")
    }
}
