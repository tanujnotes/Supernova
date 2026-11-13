package com.supernova.launcher.ui

import android.content.ComponentName
import android.os.Bundle
import com.supernova.launcher.data.WorkspaceGeometry

// A widget part way through being added: its host id, provider and where it
// goes once the system's bind screen and its own setup screen have answered.
internal data class PendingWidget(
    val widgetId: Int,
    val provider: ComponentName,
    val target: WidgetController.Target
) {
    // Written to the activity's saved state, so the add survives the launcher
    // being killed while a setup screen (a photo picker, say) is up.
    fun toBundle(): Bundle = Bundle().apply {
        putInt(WIDGET_ID, widgetId)
        putString(PROVIDER, provider.flattenToString())
        when (target) {
            is WidgetController.Target.Workspace -> {
                putString(KIND, KIND_WORKSPACE)
                putIntArray(CELL, intArrayOf(target.page, target.cellX, target.cellY))
                putStringArray(MOVE_IDS, target.displace.map { it.itemId }.toTypedArray())
                putIntArray(MOVE_X, target.displace.map { it.dCellX }.toIntArray())
                putIntArray(MOVE_Y, target.displace.map { it.dCellY }.toIntArray())
                putBooleanArray(MOVE_SNAP, target.displace.map { it.snapToCell }.toBooleanArray())
            }
            is WidgetController.Target.Card -> {
                putString(KIND, KIND_CARD)
                putString(REF, target.cardId)
            }
            is WidgetController.Target.Restored -> {
                putString(KIND, KIND_RESTORED)
                putString(REF, target.itemId)
            }
            WidgetController.Target.SearchBar -> putString(KIND, KIND_SEARCH_BAR)
        }
    }

    companion object {
        private const val WIDGET_ID = "widget_id"
        private const val PROVIDER = "provider"
        private const val KIND = "kind"
        private const val CELL = "cell"
        private const val REF = "ref"
        private const val MOVE_IDS = "move_ids"
        private const val MOVE_X = "move_x"
        private const val MOVE_Y = "move_y"
        private const val MOVE_SNAP = "move_snap"
        private const val KIND_WORKSPACE = "workspace"
        private const val KIND_CARD = "card"
        private const val KIND_RESTORED = "restored"
        private const val KIND_SEARCH_BAR = "search_bar"

        // Null for an empty or unreadable bundle: nothing was pending.
        fun fromBundle(state: Bundle): PendingWidget? {
            val widgetId = state.getInt(WIDGET_ID, 0)
            val provider = state.getString(PROVIDER)?.let(ComponentName::unflattenFromString)
            val target = targetFrom(state)
            return if (widgetId > 0 && provider != null && target != null) {
                PendingWidget(widgetId, provider, target)
            } else {
                null
            }
        }

        private fun targetFrom(state: Bundle): WidgetController.Target? = when (state.getString(KIND)) {
            KIND_WORKSPACE -> workspaceFrom(state)
            KIND_CARD -> state.getString(REF)?.let(WidgetController.Target::Card)
            KIND_RESTORED -> state.getString(REF)?.let(WidgetController.Target::Restored)
            KIND_SEARCH_BAR -> WidgetController.Target.SearchBar
            else -> null
        }

        private fun workspaceFrom(state: Bundle): WidgetController.Target.Workspace? {
            val (page, cellX, cellY) = state.getIntArray(CELL)?.toList()?.takeIf { it.size == CELL_FIELDS }
                ?: return null
            val ids = state.getStringArray(MOVE_IDS).orEmpty()
            val dx = state.getIntArray(MOVE_X) ?: IntArray(0)
            val dy = state.getIntArray(MOVE_Y) ?: IntArray(0)
            val snap = state.getBooleanArray(MOVE_SNAP) ?: BooleanArray(0)
            val moves = ids.indices.filter { it < dx.size && it < dy.size }.map { i ->
                WorkspaceGeometry.ReorderMove(ids[i], dx[i], dy[i], snap.getOrElse(i) { false })
            }
            return WidgetController.Target.Workspace(page, cellX, cellY, moves)
        }

        // page, cellX, cellY
        private const val CELL_FIELDS = 3
    }
}
