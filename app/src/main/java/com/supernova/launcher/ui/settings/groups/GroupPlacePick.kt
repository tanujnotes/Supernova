package com.supernova.launcher.ui.settings.groups

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.data.FolderPlaces
import com.supernova.launcher.data.GroupPick
import com.supernova.launcher.databinding.PageGroupEditBinding
import com.supernova.launcher.ui.LauncherViewModel

// A drawer folder page's Put it in row (#238): the tabs and folders the
// folder sits in, and a tick list of every tab and every folder that can
// hold it without a loop. OK stores the ticks that changed, as the tab's or
// folder's own Select apps would; the page binds again from what is stored.
class GroupPlacePick(
    private val binding: PageGroupEditBinding,
    private val viewModel: LauncherViewModel
) {
    fun bind(folder: DrawerGroup) {
        val pick = GroupPick.Folder(folder)
        val names = FolderPlaces.of(folder, viewModel.drawerGroups())
            .filter { viewModel.isPicked(pick, it) }
            .map { it.name }
        binding.groupPlaceValue.text = if (names.isEmpty()) {
            binding.root.context.getString(R.string.group_put_in_nowhere)
        } else {
            names.joinToString(", ")
        }
    }

    fun pick(folder: DrawerGroup) {
        val context = binding.root.context
        val places = FolderPlaces.of(folder, viewModel.drawerGroups())
        if (places.isEmpty()) return
        val pick = GroupPick.Folder(folder)
        val saved = places.map { viewModel.isPicked(pick, it) }.toBooleanArray()
        val ticks = saved.copyOf()
        val labels = places.map { place ->
            val type = context.getString(
                if (place.type == DrawerGroupType.TAB) R.string.group_type_tab else R.string.folder
            )
            "${place.name} · $type"
        }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.group_put_in)
            .setMultiChoiceItems(labels, ticks) { _, which, checked -> ticks[which] = checked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                places.indices.filter { ticks[it] != saved[it] }.forEach { i ->
                    viewModel.setGroupMembership(places[i].id, mapOf(pick to ticks[i]))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
