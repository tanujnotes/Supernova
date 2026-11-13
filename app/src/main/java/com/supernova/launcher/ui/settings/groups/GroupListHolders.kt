package com.supernova.launcher.ui.settings.groups

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.databinding.ItemGroupAddBinding
import com.supernova.launcher.databinding.ItemGroupAppBinding
import com.supernova.launcher.databinding.ItemGroupCaptionBinding
import com.supernova.launcher.databinding.ItemGroupEmptyBinding
import com.supernova.launcher.databinding.ItemGroupPreviewBinding
import com.supernova.launcher.databinding.ItemGroupRowBinding
import com.supernova.launcher.databinding.ItemSettingGroupLabelBinding

// The Drawer groups list's rows.

// The tab bar preview: a chip per tab, the one the drawer opens on picked
// out, over that tab's first apps in equal cells.
internal class GroupPreviewHolder(
    private val binding: ItemGroupPreviewBinding,
    private val icons: GroupIcons
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(row: GroupListRow.Preview) {
        binding.previewNotice.isVisible = row.barHidden
        binding.previewScroll.isVisible = !row.barHidden
        binding.previewApps.isVisible = !row.barHidden && row.apps.isNotEmpty()
        if (row.barHidden) return
        val inflater = LayoutInflater.from(binding.root.context)
        binding.previewTabs.removeAllViews()
        row.labels.forEachIndexed { index, label ->
            val chip = inflater.inflate(R.layout.item_group_chip, binding.previewTabs, false) as TextView
            chip.text = label
            chip.isSelected = index == row.selected
            binding.previewTabs.addView(chip)
        }
        // The picked tab is in view, however many come before it.
        binding.previewScroll.post {
            binding.previewTabs.getChildAt(row.selected)?.let { binding.previewScroll.scrollTo(it.left, 0) }
        }
        binding.previewApps.removeAllViews()
        repeat(APP_SLOTS) { index ->
            val cell = ItemGroupAppBinding.inflate(inflater, binding.previewApps, false)
            val app = row.apps.getOrNull(index)
            if (app == null) {
                cell.root.visibility = View.INVISIBLE
            } else {
                cell.appIcon.setImageDrawable(icons.app(app))
                cell.appLabel.text = app.label
            }
            binding.previewApps.addView(
                cell.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private companion object {
        const val APP_SLOTS = 5
    }
}

internal class GroupHeaderHolder(private val binding: ItemSettingGroupLabelBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(row: GroupListRow.Header) {
        binding.settingHeader.setText(row.title)
    }
}

// A tab or folder: a tap opens its page, its handle (or a hold on the row)
// drags it, and a screen reader gets Move up and Move down instead.
internal class GroupRowHolder(
    private val binding: ItemGroupRowBinding,
    private val icons: GroupIcons,
    private val listener: GroupListAdapter.Listener
) : RecyclerView.ViewHolder(binding.root) {

    private var moveActions = emptyList<Int>()

    @SuppressLint("ClickableViewAccessibility")
    fun bind(row: GroupListRow.Group) {
        val group = row.group
        binding.groupName.text = group.name
        val tag = when {
            row.isDefault -> R.string.group_default_tag
            group.smart -> R.string.group_smart_tag
            else -> 0
        }
        binding.groupTag.isVisible = tag != 0
        if (tag != 0) binding.groupTag.setText(tag)
        // Default wears the accent; Smart is a quiet note.
        binding.groupTag.isSelected = row.isDefault
        binding.groupSummary.text = summary(row)
        binding.groupIcon.setImageDrawable(
            if (group.type == DrawerGroupType.FOLDER) icons.folder(group) else icons.tab(row.members)
        )
        binding.root.setOnClickListener { listener.open(group) }
        binding.groupHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) listener.startDrag(this)
            false
        }
        moveActions.forEach { ViewCompat.removeAccessibilityAction(binding.root, it) }
        val resources = binding.root.resources
        moveActions = listOf(R.string.move_up to -1, R.string.move_down to 1).map { (label, delta) ->
            ViewCompat.addAccessibilityAction(binding.root, resources.getString(label)) { _, _ ->
                listener.step(group, delta)
                true
            }
        }
    }

    // "8 apps · 1 folder · kept in main tab", or "Empty".
    private fun summary(row: GroupListRow.Group): String {
        val resources = binding.root.resources
        val held = buildList {
            val apps = row.members.size
            if (apps > 0) add(resources.getQuantityString(R.plurals.group_apps_count, apps, apps))
            if (row.folders > 0) {
                add(resources.getQuantityString(R.plurals.group_folders_count, row.folders, row.folders))
            }
        }
        if (held.isEmpty()) return resources.getString(R.string.group_empty)
        val kept = if (row.kept) listOf(resources.getString(R.string.group_kept_in_main)) else emptyList()
        return (held + kept).joinToString(SEPARATOR)
    }

    private companion object {
        const val SEPARATOR = " · "
    }
}

internal class GroupAddHolder(
    private val binding: ItemGroupAddBinding,
    private val listener: GroupListAdapter.Listener
) : RecyclerView.ViewHolder(binding.root) {
    fun bind(row: GroupListRow.Add) {
        binding.groupAdd.setText(
            if (row.type == DrawerGroupType.TAB) R.string.new_tab else R.string.new_folder_group
        )
        binding.groupAdd.setOnClickListener { listener.add(row.type) }
    }
}

internal class GroupCaptionHolder(private val binding: ItemGroupCaptionBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(row: GroupListRow.Caption) {
        binding.groupCaption.setText(row.text)
    }
}

// The empty page's card for tabs or for folders: its picture, what it is,
// and its New button.
internal class GroupEmptyHolder(
    private val binding: ItemGroupEmptyBinding,
    private val listener: GroupListAdapter.Listener
) : RecyclerView.ViewHolder(binding.root) {
    fun bind(row: GroupListRow.Empty, cardColor: Int) {
        val tab = row.type == DrawerGroupType.TAB
        binding.root.backgroundTintList = ColorStateList.valueOf(cardColor)
        binding.emptyArtTabs.isVisible = tab
        binding.emptyArtFolders.isVisible = !tab
        // The picked tab and the folder tile wear the accent.
        binding.emptyArtTab.isSelected = true
        binding.emptyArtFolder.isSelected = true
        binding.emptyTitle.setText(if (tab) R.string.group_tabs_header else R.string.group_folders_header)
        binding.emptyBody.setText(if (tab) R.string.groups_tabs_blurb else R.string.groups_folders_blurb)
        binding.emptyAdd.setText(if (tab) R.string.new_tab else R.string.new_folder_group)
        binding.emptyAdd.setOnClickListener { listener.add(row.type) }
    }
}
