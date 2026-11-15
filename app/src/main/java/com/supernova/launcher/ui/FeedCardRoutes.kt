package com.supernova.launcher.ui

import android.graphics.drawable.Drawable
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.supernova.launcher.R
import com.supernova.launcher.data.LaunchTarget
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.feed.FeaturedType
import com.supernova.launcher.feed.FeedCard
import java.util.UUID

// Custom cards, wherever they show (the feed, the drawer, search): how one
// is drawn, what its targets launch, its menu, adding one, and its editor.
// Each surface takes a binder from here, so the cards look and act the same
// on all of them.
class FeedCardRoutes(
    private val activity: AppCompatActivity,
    private val viewModel: LauncherViewModel,
    private val actions: () -> ActionRunner,
    private val widgets: () -> WidgetController,
    // A ready-made card turned on from the add sheet that needs its access.
    private val requestCalendar: () -> Unit,
    private val requestContacts: () -> Unit
) {

    private val widgetFlow = CustomCardEditorSheet.WidgetFlow(
        pick = { onPicked -> widgets().showPickerForCard(onPicked) },
        bind = { info, cardId -> widgets().bindToCard(info, cardId) }
    )

    fun edit(card: FeedCard?) {
        CustomCardEditorSheet.show(activity, viewModel, card, widgetFlow = widgetFlow)
    }

    // Add card: the add sheet.
    fun add() {
        FeedAddSheet.show(
            activity, viewModel,
            onWidget = ::addWidget,
            onShortcuts = { edit(null) },
            requestCalendar = requestCalendar,
            requestContacts = requestContacts
        )
    }

    // A widget card goes straight to the picker; the card is made once the
    // widget is bound (LauncherRepository.setCardWidget), with no editor.
    fun addWidget() {
        widgetFlow.pick { info -> widgetFlow.bind(info, UUID.randomUUID().toString()) }
    }

    fun launchTarget(type: FeaturedType, target: String) {
        when (type) {
            FeaturedType.APP -> actions().launchApp(target)
            FeaturedType.SHORTCUT -> actions().launchShortcut(target)
            FeaturedType.WIDGET -> Unit
        }
    }

    // One binder per surface: the look is shared, the after-launch step is
    // not (search and the drawer close themselves, the feed stays). In the
    // feed a long-press picks the card up, and the menu opens when it is let
    // go unmoved (FeedCardDrag); elsewhere the long-press is the menu.
    fun binder(afterLaunch: (() -> Unit)?, inFeed: Boolean = false): CardViewBinder =
        CardViewBinder(
            CardViewBinder.Callbacks(
                onLaunchTarget = { type, target, _ ->
                    launchTarget(type, target)
                    afterLaunch?.invoke()
                },
                widgetView = { card -> widgets().cardWidgetView(card) },
                iconForTarget = ::iconForCardTarget,
                labelForTarget = ::labelForCardTarget,
                onMenu = { card, anchor -> showCardMenu(card, anchor) },
                caption = ::caption
            ),
            longPressMenu = !inFeed
        )

    // Why a card sits at the top: it is pinned there.
    private fun caption(card: FeedCard): CharSequence? =
        activity.getString(R.string.card_caption_pinned).takeIf { card.pinned }

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

    // The card menu: Edit, Pin to top (or Unpin), Remove with an Undo. Cards
    // move by dragging.
    private fun showCardMenu(card: FeedCard, anchor: View) {
        val entries = listOf<Pair<Int, () -> Unit>>(
            R.string.card_menu_edit to { edit(card) },
            (if (card.pinned) R.string.card_menu_unpin else R.string.card_menu_pin) to
                { viewModel.setFeedCardPinned(card.id, !card.pinned) },
            R.string.card_menu_remove to { remove(card, anchor) }
        )
        ChoiceMenu.showActions(
            anchor, viewModel.state.value.prefs, entries.map { activity.getString(it.first) }
        ) { which -> entries[which].second() }
    }

    // Undo puts the card back in the slot it had, not at the end.
    private fun remove(card: FeedCard, anchor: View) {
        val index = viewModel.feedCards().indexOfFirst { it.id == card.id }
        viewModel.removeFeedCard(card.id)
        com.google.android.material.snackbar.Snackbar
            .make(
                anchor.rootView, R.string.card_removed,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG
            )
            .setAction(R.string.undo) { viewModel.restoreFeedCard(card, index.coerceAtLeast(0)) }
            .show()
    }
}
