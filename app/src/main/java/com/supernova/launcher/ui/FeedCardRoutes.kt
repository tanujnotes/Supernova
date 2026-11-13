package com.supernova.launcher.ui

import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.view.View
import android.widget.PopupMenu
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

    // One binder per surface: the look is shared, the after-launch step is
    // not (search and the drawer close themselves, the feed stays).
    fun binder(afterLaunch: (() -> Unit)?): CardViewBinder =
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
                onHistory = { card, _ -> showCardHistory(card) }
            )
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

    // The card menu: Settings (edit) and Pin.
    private fun showCardMenu(card: FeedCard, anchor: View) {
        val menu = PopupMenu(activity, anchor)
        menu.menu.add(0, 1, 0, R.string.card_menu_edit)
        menu.menu.add(0, 2, 1, if (card.pinned) R.string.card_menu_unpin else R.string.card_menu_pin)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> edit(card)
                2 -> viewModel.setFeedCardPinned(card.id, !card.pinned)
            }
            true
        }
        menu.show()
    }
}
