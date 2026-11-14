package com.supernova.launcher.ui

import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.supernova.launcher.R
import com.supernova.launcher.data.LaunchTarget
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.feed.CardActivation
import com.supernova.launcher.feed.FeaturedType
import com.supernova.launcher.feed.FeedCard

// Custom cards, wherever they show (the feed, the drawer, search): how one
// is drawn, what its targets launch, and its menu, history and editor. Each
// surface takes a binder from here, so the cards look and act the same on
// all of them.
class FeedCardRoutes(
    private val activity: AppCompatActivity,
    private val viewModel: LauncherViewModel,
    private val actions: () -> ActionRunner,
    private val activation: () -> CardActivation,
    private val widgets: () -> WidgetController
) {

    fun edit(card: FeedCard?) {
        CustomCardEditorSheet.show(
            activity, viewModel, card,
            widgetFlow = CustomCardEditorSheet.WidgetFlow(
                pick = { onPicked -> widgets().showPickerForCard(onPicked) },
                bind = { info, cardId -> widgets().bindToCard(info, cardId) }
            )
        )
    }

    fun launchTarget(type: FeaturedType, target: String) {
        when (type) {
            FeaturedType.APP -> actions().launchApp(target)
            FeaturedType.SHORTCUT -> actions().launchShortcut(target)
            FeaturedType.WIDGET -> Unit
        }
    }

    // Moving a card up or down the feed, from its menu. Only the feed has
    // an order to move in; the drawer and search pass none.
    interface Moves {
        fun canMove(key: String, up: Boolean): Boolean

        fun move(key: String, up: Boolean)
    }

    // One binder per surface: the look is shared, the after-launch step is
    // not (search and the drawer close themselves, the feed stays).
    fun binder(afterLaunch: (() -> Unit)?, moves: Moves? = null): CardViewBinder =
        CardViewBinder(
            CardViewBinder.Callbacks(
                onLaunchTarget = { type, target, _ ->
                    launchTarget(type, target)
                    afterLaunch?.invoke()
                },
                widgetView = { card -> widgets().cardWidgetView(card) },
                iconForTarget = ::iconForCardTarget,
                labelForTarget = ::labelForCardTarget,
                onMenu = { card, anchor -> showCardMenu(card, anchor, moves) },
                onHistory = { card, _ -> showCardHistory(card) }
            ),
            // In the feed a long-press picks the card up instead.
            longPressMenu = moves == null
        )

    // The history mark: when the card last activated (its conditions all
    // held), or why it never will.
    private fun showCardHistory(card: FeedCard) {
        val text = when {
            !card.hasConditions -> activity.getString(R.string.card_history_none)
            else -> activation().lastActivatedAt(card.id)?.let { at ->
                activity.getString(
                    R.string.card_history_at,
                    DateUtils.getRelativeTimeSpanString(
                        at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                    )
                )
            } ?: activity.getString(R.string.card_history_never)
        }
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    private fun appForCardTarget(target: String): LaunchableApp? {
        val (body, serial) = LaunchTarget.split(target)
        val pkg = if (body.contains('|')) body.substringBefore('|') else body.substringBefore('/')
        val cls = if (body.contains('|')) "" else body.substringAfter('/', "")
        if (pkg.isEmpty()) return null
        // Shortcut targets fall back to their app: the catalog resolves by
        // package when the class is absent.
        return viewModel.state.value.apps.firstOrNull {
            it.packageName == pkg && (cls.isEmpty() || it.className == cls) &&
                it.userSerial == serial
        } ?: LaunchableApp(pkg, cls, "", serial)
    }

    private fun iconForCardTarget(target: String): Drawable? {
        val app = appForCardTarget(target) ?: return null
        val body = LaunchTarget.split(target).first
        if (body.contains('|')) {
            val id = body.substringAfter('|')
            viewModel.shortcutsFor(app.packageName, app.userSerial)
                .firstOrNull { it.id == id }
                ?.let { shortcut -> viewModel.appCatalog().shortcutIcon(shortcut)?.let { return it } }
        }
        return viewModel.appCatalog().iconFor(app)
    }

    // App label, or "App: shortcut" for a shortcut target; null when the
    // target no longer resolves (uninstalled).
    private fun labelForCardTarget(target: String): String? {
        val app = appForCardTarget(target) ?: return null
        val appLabel = app.label.takeIf { it.isNotBlank() }
            ?: viewModel.state.value.apps.firstOrNull {
                it.packageName == app.packageName && it.userSerial == app.userSerial
            }?.label
        val body = LaunchTarget.split(target).first
        if (!body.contains('|')) return appLabel
        val id = body.substringAfter('|')
        val shortcut = viewModel.shortcutsFor(app.packageName, app.userSerial).firstOrNull { it.id == id }
        val shortcutLabel = (shortcut?.shortLabel ?: shortcut?.longLabel)?.toString()
        return when {
            shortcutLabel != null && appLabel != null -> "$appLabel: $shortcutLabel"
            else -> shortcutLabel ?: appLabel
        }
    }

    // The card menu: Settings (edit), Pin, Move up / Move down (in the feed,
    // where there is room to move), and Delete with an Undo.
    private fun showCardMenu(card: FeedCard, anchor: View, moves: Moves?) {
        val entries = buildList<Pair<Int, () -> Unit>> {
            add(R.string.card_menu_edit to { edit(card) })
            add(
                (if (card.pinned) R.string.card_menu_unpin else R.string.card_menu_pin) to
                    { viewModel.setFeedCardPinned(card.id, !card.pinned) }
            )
            if (moves?.canMove(card.id, up = true) == true) {
                add(R.string.card_menu_move_up to { moves.move(card.id, up = true) })
            }
            if (moves?.canMove(card.id, up = false) == true) {
                add(R.string.card_menu_move_down to { moves.move(card.id, up = false) })
            }
            add(R.string.card_delete to { delete(card, anchor) })
        }
        ChoiceMenu.showActions(
            anchor, viewModel.state.value.prefs, entries.map { activity.getString(it.first) }
        ) { which -> entries[which].second() }
    }

    // Undo puts the card back in the slot it had, not at the end.
    private fun delete(card: FeedCard, anchor: View) {
        val index = viewModel.feedCards().indexOfFirst { it.id == card.id }
        viewModel.removeFeedCard(card.id)
        com.google.android.material.snackbar.Snackbar
            .make(
                anchor.rootView, R.string.card_deleted,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            )
            .setAction(R.string.undo) { viewModel.restoreFeedCard(card, index.coerceAtLeast(0)) }
            .show()
    }
}
