package com.supernova.launcher.ui.settings.groups

import android.graphics.Canvas
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroupType
import com.supernova.launcher.ui.dp

// Dragging a group to a new place among its own kind, by its handle or by a
// hold on its row: a tab among the tabs, a folder among the folders. The
// row lifts off its card while it is held. onDragging brackets the drag;
// onDropped names the type whose order changed, if any did.
class GroupReorder(
    private val adapter: GroupListAdapter,
    private val onDragging: (Boolean) -> Unit,
    private val onDropped: (DrawerGroupType?) -> Unit
) : ItemTouchHelper.Callback() {

    private var moved: DrawerGroupType? = null

    private fun group(holder: RecyclerView.ViewHolder) =
        (adapter.row(holder.bindingAdapterPosition) as? GroupListRow.Group)?.group

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
        if (group(viewHolder) == null) 0 else makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

    override fun canDropOver(
        recyclerView: RecyclerView,
        current: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val type = group(current)?.type
        return type != null && type == group(target)?.type
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val type = group(viewHolder)?.type
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (type == null || from < 0 || to < 0) return false
        adapter.move(from, to)
        moved = type
        return true
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || viewHolder == null) return
        viewHolder.itemView.setBackgroundResource(R.drawable.bg_group_lifted)
        // The card's decoration draws the band it left.
        viewHolder.itemView.isActivated = true
        onDragging(true)
    }

    // The helper raises a dragged row just over its neighbours; this one
    // stands well clear, with a shadow.
    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        if (isCurrentlyActive) viewHolder.itemView.elevation = recyclerView.dp(LIFT_DP).toFloat()
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.background = null
        viewHolder.itemView.elevation = 0f
        viewHolder.itemView.isActivated = false
        recyclerView.invalidateItemDecorations()
        onDragging(false)
        onDropped(moved)
        moved = null
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    private companion object {
        const val LIFT_DP = 8
    }
}
