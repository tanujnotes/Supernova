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
import com.supernova.launcher.data.LauncherItem

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
