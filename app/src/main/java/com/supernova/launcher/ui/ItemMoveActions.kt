package com.supernova.launcher.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.supernova.launcher.R
import com.supernova.launcher.data.AccessibleMoves
import com.supernova.launcher.data.AccessibleMoves.Move
import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.WorkspaceGeometry

// Moving a home screen icon is a long-press and a drag, which a screen
// reader or a switch can't perform. Each dock and desktop icon offers the
// same moves as actions instead: a cell over, a page over, into the dock or
// out of it. The actions are listed only when an accessibility service asks
// for the icon, so they cost a bound icon nothing.
class ItemMoveActions(private val host: Host) {

    interface Host {
        fun state(): LauncherUiState
        fun moveToCell(itemId: String, target: AccessibleMoves.Target.Cell)
        fun moveToDock(itemId: String, rank: Int)
        // A widget's new cells; done says whether there was room for them.
        fun placeWidget(itemId: String, rect: WorkspaceGeometry.CellRect, done: (Boolean) -> Unit)
        fun announce(message: Int)
    }

    fun attach(view: View, itemId: String) {
        ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                offered(itemId).forEach { move ->
                    val (id, label) = ACTIONS.getValue(move)
                    info.addAction(AccessibilityActionCompat(id, host.context.getString(label)))
                }
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val move = MOVES_BY_ACTION[action] ?: return super.performAccessibilityAction(host, action, args)
                perform(itemId, move)
                return true
            }
        })
    }

    // A widget is moved and resized by dragging its frame. As actions: a
    // cell over in each direction, and a cell wider, narrower, taller or
    // shorter (from its right and bottom edges), each only where the grid
    // has room for it.
    fun attachWidget(view: View, itemId: String) {
        ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                widgetChanges(itemId).forEach { change ->
                    info.addAction(AccessibilityActionCompat(change.action, host.context.getString(change.label)))
                }
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val change = widgetChanges(itemId).firstOrNull { it.action == action }
                    ?: return super.performAccessibilityAction(host, action, args)
                this@ItemMoveActions.host.placeWidget(itemId, change.rect) { placed ->
                    val said = when {
                        !placed -> R.string.a11y_no_room
                        change.resizes -> R.string.a11y_resized
                        else -> R.string.a11y_moved
                    }
                    this@ItemMoveActions.host.announce(said)
                }
                return true
            }
        })
    }

    private class WidgetChange(
        val action: Int,
        val label: Int,
        val rect: WorkspaceGeometry.CellRect,
        val resizes: Boolean
    )

    private fun widgetChanges(itemId: String): List<WidgetChange> {
        val state = host.state()
        val widget = movable(state, itemId)
            ?.takeIf { it.container == ContainerType.WORKSPACE } ?: return emptyList()
        val cols = state.prefs.gridCols
        val rows = state.prefs.gridRows
        val r = WorkspaceGeometry.itemRect(widget, cols, rows)
        val width = r.right - r.left
        val height = r.bottom - r.top
        fun moved(dx: Float, dy: Float) =
            WorkspaceGeometry.CellRect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
        return listOfNotNull(
            WidgetChange(R.id.a11y_move_left, R.string.a11y_move_left, moved(-1f, 0f), false).takeIf { r.left >= 1f },
            WidgetChange(R.id.a11y_move_right, R.string.a11y_move_right, moved(1f, 0f), false)
                .takeIf { r.right <= cols - 1f },
            WidgetChange(R.id.a11y_move_up, R.string.a11y_move_up, moved(0f, -1f), false).takeIf { r.top >= 1f },
            WidgetChange(R.id.a11y_move_down, R.string.a11y_move_down, moved(0f, 1f), false)
                .takeIf { r.bottom <= rows - 1f },
            WidgetChange(R.id.a11y_wider, R.string.a11y_wider, r.copy(right = r.right + 1f), true)
                .takeIf { r.right <= cols - 1f },
            WidgetChange(R.id.a11y_narrower, R.string.a11y_narrower, r.copy(right = r.right - 1f), true)
                .takeIf { width >= 2f },
            WidgetChange(R.id.a11y_taller, R.string.a11y_taller, r.copy(bottom = r.bottom + 1f), true)
                .takeIf { r.bottom <= rows - 1f },
            WidgetChange(R.id.a11y_shorter, R.string.a11y_shorter, r.copy(bottom = r.bottom - 1f), true)
                .takeIf { height >= 2f }
        )
    }

    // Nothing while the desktop is locked: it refuses a drag, and so these.
    private fun movable(state: LauncherUiState, itemId: String): LauncherItem? =
        state.model.items.firstOrNull { it.id == itemId }.takeUnless { state.prefs.desktopLocked }

    private fun grid(state: LauncherUiState) = AccessibleMoves.Grid(
        state.prefs.gridCols,
        state.prefs.gridRows,
        if (state.prefs.dockEnabled) state.prefs.dockCount * state.prefs.dockPages else 0
    )

    private fun offered(itemId: String): List<Move> {
        val state = host.state()
        return movable(state, itemId)?.let { AccessibleMoves.offered(state.model, it, grid(state)) }.orEmpty()
    }

    private fun perform(itemId: String, move: Move) {
        val state = host.state()
        val item = movable(state, itemId) ?: return
        val target = AccessibleMoves.target(state.model, item, move, grid(state), state.currentPage)
        when (target) {
            is AccessibleMoves.Target.Cell -> host.moveToCell(itemId, target)
            is AccessibleMoves.Target.Dock -> host.moveToDock(itemId, target.rank)
            null -> Unit
        }
        host.announce(if (target == null) R.string.a11y_no_room else R.string.a11y_moved)
    }

    private companion object {
        val ACTIONS: Map<Move, Pair<Int, Int>> = mapOf(
            Move.LEFT to (R.id.a11y_move_left to R.string.a11y_move_left),
            Move.RIGHT to (R.id.a11y_move_right to R.string.a11y_move_right),
            Move.UP to (R.id.a11y_move_up to R.string.a11y_move_up),
            Move.DOWN to (R.id.a11y_move_down to R.string.a11y_move_down),
            Move.PREVIOUS_PAGE to (R.id.a11y_move_previous_page to R.string.a11y_move_previous_page),
            Move.NEXT_PAGE to (R.id.a11y_move_next_page to R.string.a11y_move_next_page),
            Move.TO_DOCK to (R.id.a11y_move_to_dock to R.string.a11y_move_to_dock),
            Move.TO_HOME to (R.id.a11y_move_to_home to R.string.a11y_move_to_home)
        )
        val MOVES_BY_ACTION: Map<Int, Move> = ACTIONS.entries.associate { (move, action) -> action.first to move }
    }
}
