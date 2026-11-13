package com.supernova.launcher.ui.settings.groups

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.databinding.ItemGroupAddBinding
import com.supernova.launcher.databinding.ItemGroupCaptionBinding
import com.supernova.launcher.databinding.ItemGroupEmptyBinding
import com.supernova.launcher.databinding.ItemGroupPreviewBinding
import com.supernova.launcher.databinding.ItemGroupRowBinding
import com.supernova.launcher.databinding.ItemSettingGroupLabelBinding

// The Drawer groups page's list. A drag moves a row here first, with the tab
// bar preview following, and the page stores the order when it is dropped.
class GroupListAdapter(
    private val icons: GroupIcons,
    private val listener: Listener
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun open(group: DrawerGroup)
        fun add(type: DrawerGroupType)
        // A step up or down among its own kind, for a screen reader, which
        // cannot drag.
        fun step(group: DrawerGroup, delta: Int)
        // A press on a row's handle.
        fun startDrag(holder: RecyclerView.ViewHolder)
    }

    private var rows: List<GroupListRow> = emptyList()

    // The colour of the cards that are views of their own (the empty page's).
    var cardColor = 0

    fun row(position: Int): GroupListRow? = rows.getOrNull(position)

    // A row on a card: a group, or its card's New row.
    fun isCardRow(position: Int): Boolean = row(position).let { it is GroupListRow.Group || it is GroupListRow.Add }

    fun submit(next: List<GroupListRow>) {
        val diff = DiffUtil.calculateDiff(RowDiff(rows, next))
        rows = next
        diff.dispatchUpdatesTo(this)
    }

    // One type's groups, in the order the list shows them now.
    fun names(type: DrawerGroupType): List<String> =
        rows.filterIsInstance<GroupListRow.Group>().filter { it.group.type == type }.map { it.group.name }

    // A dragged row passing another: they trade places, and the preview's
    // tabs follow.
    fun move(from: Int, to: Int) {
        val moved = rows.toMutableList()
        moved.add(to, moved.removeAt(from))
        val at = moved.indexOfFirst { it is GroupListRow.Preview }
        val preview = moved.getOrNull(at) as? GroupListRow.Preview
        rows = moved
        notifyItemMoved(from, to)
        val tabs = names(DrawerGroupType.TAB)
        if (preview != null && preview.tabs != tabs) {
            rows = moved.also { it[at] = preview.copy(tabs = tabs) }
            notifyItemChanged(at)
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is GroupListRow.Preview -> R.layout.item_group_preview
        is GroupListRow.Header -> R.layout.item_setting_group_label
        is GroupListRow.Group -> R.layout.item_group_row
        is GroupListRow.Add -> R.layout.item_group_add
        is GroupListRow.Caption -> R.layout.item_group_caption
        is GroupListRow.Empty -> R.layout.item_group_empty
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            R.layout.item_group_preview ->
                GroupPreviewHolder(ItemGroupPreviewBinding.inflate(inflater, parent, false), icons)
            R.layout.item_setting_group_label ->
                GroupHeaderHolder(ItemSettingGroupLabelBinding.inflate(inflater, parent, false))
            R.layout.item_group_row ->
                GroupRowHolder(ItemGroupRowBinding.inflate(inflater, parent, false), icons, listener)
            R.layout.item_group_add -> GroupAddHolder(ItemGroupAddBinding.inflate(inflater, parent, false), listener)
            R.layout.item_group_caption ->
                GroupCaptionHolder(ItemGroupCaptionBinding.inflate(inflater, parent, false))
            else -> GroupEmptyHolder(ItemGroupEmptyBinding.inflate(inflater, parent, false), listener)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is GroupListRow.Preview -> (holder as GroupPreviewHolder).bind(row)
            is GroupListRow.Header -> (holder as GroupHeaderHolder).bind(row)
            is GroupListRow.Group -> (holder as GroupRowHolder).bind(row)
            is GroupListRow.Add -> (holder as GroupAddHolder).bind(row)
            is GroupListRow.Caption -> (holder as GroupCaptionHolder).bind(row)
            is GroupListRow.Empty -> (holder as GroupEmptyHolder).bind(row, cardColor)
        }
    }

    private class RowDiff(
        private val old: List<GroupListRow>,
        private val new: List<GroupListRow>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            old[oldItemPosition].key == new[newItemPosition].key

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            old[oldItemPosition] == new[newItemPosition]
    }
}
