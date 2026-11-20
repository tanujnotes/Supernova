package com.supernova.launcher.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.doAfterTextChanged
import com.supernova.launcher.R
import com.supernova.launcher.databinding.SheetCardEditorBinding
import com.supernova.launcher.feed.CardAction
import com.supernova.launcher.feed.CardItems
import com.supernova.launcher.feed.FeaturedType
import com.supernova.launcher.feed.FeedCard
import com.supernova.launcher.feed.FeedCard.Companion.WIDGET_HEIGHT_MAX_DP
import com.supernova.launcher.feed.FeedCard.Companion.WIDGET_HEIGHT_MIN_DP
import com.supernova.launcher.feed.FeedCard.Companion.WIDGET_HEIGHT_STEP_DP
import java.util.UUID

// The card editor, one sheet for both kinds of card:
// - apps and shortcuts: a live preview, the list (up to six, the first
//   optionally a large tile), Pin to top;
// - a widget: the widget with Change, its height (Fill the page or a set
//   height), Card background, Pin to top, and shortcuts under it.
// Only Save commits; Cancel and Back leave the card as it was. Remove offers
// Undo.
object CustomCardEditorSheet {

    // The host's widget flow for a widget card: the picker, then the bind
    // into a card id already saved. Null where no widget can be bound
    // (Settings): Change then hides.
    class WidgetFlow(
        val pick: (onPicked: (android.appwidget.AppWidgetProviderInfo) -> Unit) -> Unit,
        val bind: (info: android.appwidget.AppWidgetProviderInfo, cardId: String) -> Unit
    )

    // The editor's working copy, committed only on Save.
    private class Draft(base: FeedCard) {
        val items = CardItems.of(base).toMutableList()
        var widgetHeightDp = base.widgetHeight
        var fillPage = base.fillPage
    }

    fun show(
        activity: AppCompatActivity,
        viewModel: LauncherViewModel,
        existing: FeedCard?,
        widgetFlow: WidgetFlow? = null,
        // What the Undo bar after Remove sits above (Settings' tab bar);
        // null puts it at the screen's bottom.
        undoAnchor: View? = null
    ) {
        val binding = SheetCardEditorBinding.inflate(LayoutInflater.from(activity))
        val dialog = Sheets.open(activity)
        dialog.setContentView(binding.root)
        // A stray drag or tap outside never throws the edits away; Back and
        // Cancel do, on purpose.
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.onBackPressedDispatcher.addCallback(dialog) { dialog.dismiss() }

        val base = existing ?: FeedCard(id = UUID.randomUUID().toString())
        val widget = CardItems.isWidget(base)
        val draft = Draft(base)
        binding.cardName.setText(base.name)
        binding.cardPin.isChecked = base.pinned
        binding.cardFrame.isChecked = base.showFrame
        binding.cardLargeTile.isChecked = CardItems.largeTile(base)

        fun buildCard(): FeedCard = CardItems.apply(
            base.copy(
                name = binding.cardName.text?.toString()?.trim().orEmpty(),
                pinned = binding.cardPin.isChecked,
                showFrame = if (widget) binding.cardFrame.isChecked else base.showFrame,
                widgetHeightDp = draft.widgetHeightDp,
                fillPage = widget && draft.fillPage
            ),
            draft.items,
            binding.cardLargeTile.isChecked
        )

        // ---- Which rows this kind of card has ----
        val shortcutsOnly = if (widget) View.GONE else View.VISIBLE
        val widgetOnly = if (widget) View.VISIBLE else View.GONE
        binding.cardPreviewWell.visibility = shortcutsOnly
        binding.cardLargeTileRow.visibility = shortcutsOnly
        binding.cardWidgetRow.visibility = widgetOnly
        binding.cardHeightSection.visibility = widgetOnly
        binding.cardFrameRow.visibility = widgetOnly
        binding.cardItemsLabel.setText(if (widget) R.string.card_widget_items else R.string.card_items_label)

        // ---- Preview (apps and shortcuts) ----
        val previewBinder = CardViewBinder(
            CardViewBinder.Callbacks(
                onLaunchTarget = { _, _, _ -> },
                widgetView = { null },
                iconForTarget = { target -> iconForTarget(viewModel, target) },
                labelForTarget = { target -> labelForTarget(viewModel, target) },
                onMenu = null
            )
        )
        val look = com.supernova.launcher.ui.theme.Styles
            .resolve(activity, viewModel.state.value.prefs)
            .of(com.supernova.launcher.ui.theme.SurfaceId.CARDS)
        previewBinder.setAppearance(look.surface, look.text, look.accent)
        val previewBinding = previewBinder.inflate(binding.cardPreview)
        // The well gives the preview its own margin.
        (previewBinding.root.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
        binding.cardPreview.addView(previewBinding.root)
        val refreshPreview = { if (!widget) previewBinder.bind(previewBinding, buildCard()) }
        binding.cardName.doAfterTextChanged { refreshPreview() }
        binding.cardLargeTile.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.cardLargeTileRow.setOnClickListener { binding.cardLargeTile.toggle() }
        binding.cardPinRow.setOnClickListener { binding.cardPin.toggle() }
        binding.cardFrameRow.setOnClickListener { binding.cardFrame.toggle() }

        // ---- The widget, and Change ----
        if (widget) {
            val info = base.appWidgetId?.let {
                android.appwidget.AppWidgetManager.getInstance(activity).getAppWidgetInfo(it)
            }
            binding.cardWidgetLabel.text = info?.loadLabel(activity.packageManager)
                ?: activity.getString(R.string.card_widget_unavailable)
            binding.cardWidgetIcon.setImageDrawable(info?.loadIcon(activity, 0))
            binding.cardWidgetChange.visibility = if (widgetFlow == null) View.GONE else View.VISIBLE
            binding.cardWidgetChange.setOnClickListener {
                widgetFlow?.pick { picked ->
                    // Saved first, so the new widget binds to the card as
                    // edited; the editor closes and the feed shows it.
                    viewModel.upsertFeedCard(buildCard())
                    widgetFlow.bind(picked, base.id)
                    dialog.dismiss()
                }
            }
        }

        bindHeight(activity, binding, draft)
        // An apps-and-shortcuts card saves once it holds one: empty, it was
        // a blank plate in the feed.
        bindItems(activity, viewModel, binding, draft) {
            refreshPreview()
            binding.cardSave.isEnabled = widget || draft.items.isNotEmpty()
        }

        // ---- Buttons ----
        binding.cardRemove.visibility = if (existing == null) View.GONE else View.VISIBLE
        binding.cardRemove.setOnClickListener {
            val removed = existing ?: return@setOnClickListener
            val index = viewModel.feedCards().indexOfFirst { it.id == removed.id }
            viewModel.removeFeedCard(removed.id)
            dialog.dismiss()
            com.google.android.material.snackbar.Snackbar
                .make(
                    activity.findViewById(android.R.id.content),
                    R.string.card_removed,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                )
                .setAction(R.string.undo) { viewModel.restoreFeedCard(removed, index.coerceAtLeast(0)) }
                .apply { if (undoAnchor != null) anchorView = undoAnchor }
                .show()
        }
        binding.cardCancel.setOnClickListener { dialog.dismiss() }
        binding.cardSave.setOnClickListener {
            viewModel.upsertFeedCard(buildCard())
            dialog.dismiss()
        }

        refreshPreview()
        dialog.show()
    }

    // A widget card's height: Fill the page, or a set height on the stepper.
    private fun bindHeight(activity: AppCompatActivity, binding: SheetCardEditorBinding, draft: Draft) {
        fun refresh() {
            binding.cardHeightHint.visibility = if (draft.fillPage) View.VISIBLE else View.GONE
            binding.cardWidgetHeightStepper.visibility = if (draft.fillPage) View.GONE else View.VISIBLE
            binding.cardWidgetHeightValue.text =
                activity.getString(R.string.card_widget_height_value, draft.widgetHeightDp)
            binding.cardWidgetHeightSeek.progress =
                (draft.widgetHeightDp - WIDGET_HEIGHT_MIN_DP) / WIDGET_HEIGHT_STEP_DP
        }
        binding.cardHeightGroup.check(if (draft.fillPage) R.id.cardHeightFill else R.id.cardHeightSet)
        binding.cardHeightGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            draft.fillPage = id == R.id.cardHeightFill
            refresh()
        }
        binding.cardWidgetHeightSeek.max = (WIDGET_HEIGHT_MAX_DP - WIDGET_HEIGHT_MIN_DP) / WIDGET_HEIGHT_STEP_DP
        binding.cardWidgetHeightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                draft.widgetHeightDp = WIDGET_HEIGHT_MIN_DP + progress * WIDGET_HEIGHT_STEP_DP
                refresh()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        binding.cardWidgetHeightMinus.setOnClickListener {
            draft.widgetHeightDp = (draft.widgetHeightDp - WIDGET_HEIGHT_STEP_DP).coerceAtLeast(WIDGET_HEIGHT_MIN_DP)
            refresh()
        }
        binding.cardWidgetHeightPlus.setOnClickListener {
            draft.widgetHeightDp = (draft.widgetHeightDp + WIDGET_HEIGHT_STEP_DP).coerceAtMost(WIDGET_HEIGHT_MAX_DP)
            refresh()
        }
        refresh()
    }

    // The list as chips: a tap moves one earlier or later (the first can be
    // the large tile), its × takes it out, and Add picks an app or shortcut.
    private fun bindItems(
        activity: AppCompatActivity,
        viewModel: LauncherViewModel,
        binding: SheetCardEditorBinding,
        draft: Draft,
        onChanged: () -> Unit
    ) {
        val items = draft.items
        lateinit var refresh: () -> Unit
        // One list of apps with their icons: a tap adds the app, or one of
        // its shortcuts when it has any. It was a text list of names, and a
        // separate shortcut list that dead-ended on an app with none.
        fun add() {
            ActionPickerSheet.showApps(activity, viewModel, activity.getString(R.string.card_add_action)) { pick ->
                when (pick) {
                    is ActionPick.App -> items.add(
                        CardAction(
                            FeaturedType.APP.name,
                            "${pick.app.packageName}/${pick.app.className}@${pick.app.userSerial}",
                            pick.app.label
                        )
                    )
                    is ActionPick.Shortcut -> items.add(
                        CardAction(
                            FeaturedType.SHORTCUT.name,
                            "${pick.app.packageName}|${pick.shortcutId}@${pick.app.userSerial}",
                            pick.label
                        )
                    )
                    else -> return@showApps
                }
                refresh()
            }
        }
        fun move(at: Int, anchor: View) {
            val entries = buildList<Pair<Int, () -> Unit>> {
                if (at > 0) add(R.string.card_item_earlier to { items.add(at - 1, items.removeAt(at)) })
                if (at < items.lastIndex) add(R.string.card_item_later to { items.add(at + 1, items.removeAt(at)) })
            }
            if (entries.isEmpty()) return
            ChoiceMenu.showActions(anchor, viewModel.state.value.prefs, entries.map { activity.getString(it.first) }) {
                entries[it].second()
                refresh()
            }
        }
        refresh = {
            val flow = binding.cardItems
            flow.removeAllViews()
            items.forEachIndexed { index, item ->
                val label = item.label.ifBlank {
                    labelForTarget(viewModel, item.target)
                        ?: com.supernova.launcher.data.LaunchTarget.split(item.target).first
                }
                flow.addView(itemChip(activity, iconForTarget(viewModel, item.target), label,
                    onTap = { anchor -> move(index, anchor) },
                    onRemove = {
                        items.removeAt(index)
                        refresh()
                    }
                ))
            }
            if (items.size < CardItems.MAX) {
                flow.addView(addChip(activity) { add() })
            }
            binding.cardItemsCount.text = activity.getString(R.string.card_items_count, items.size, CardItems.MAX)
            onChanged()
        }
        refresh()
    }

    private fun itemChip(
        context: Context,
        icon: android.graphics.drawable.Drawable?,
        label: String,
        onTap: (View) -> Unit,
        onRemove: () -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = AppCompatResources.getDrawable(context, R.drawable.bg_item_chip)
        foreground = AppCompatResources.getDrawable(context, R.drawable.ripple_icon)
        minimumHeight = context.dp(44)
        setPaddingRelative(context.dp(if (icon != null) 8 else 14), 0, 0, 0)
        // An app icon's shadow falls outside it.
        clipChildren = false
        contentDescription = label
        setOnClickListener(onTap)
        if (icon != null) {
            val size = context.dp(24)
            addView(
                ImageView(context).apply { setImageDrawable(icon.constantState?.newDrawable()?.mutate() ?: icon) },
                LinearLayout.LayoutParams(size, size).apply { marginEnd = context.dp(8) }
            )
        }
        addView(TextView(context).apply {
            text = label
            maxLines = 1
            maxWidth = context.dp(180)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(context.getColor(R.color.textPrimary))
            textSize = 14f
        })
        // A text ×, not a drawable: the remove button.
        addView(
            TextView(context).apply {
                text = "×"
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(context.getColor(R.color.textSecondary))
                background = AppCompatResources.getDrawable(context, R.drawable.ripple_icon)
                contentDescription = context.getString(R.string.card_item_remove, label)
                setOnClickListener { onRemove() }
            },
            LinearLayout.LayoutParams(context.dp(40), context.dp(44))
        )
    }

    private fun addChip(context: Context, onTap: () -> Unit): View = TextView(context).apply {
        text = context.getString(R.string.card_items_add)
        gravity = Gravity.CENTER
        minHeight = context.dp(44)
        setPadding(context.dp(16), 0, context.dp(16), 0)
        background = AppCompatResources.getDrawable(context, R.drawable.bg_dashed_pill)
        foreground = AppCompatResources.getDrawable(context, R.drawable.ripple_icon)
        setTextColor(context.getColor(R.color.textPrimary))
        textSize = 14f
        setOnClickListener { onTap() }
    }

    // Preview-side target resolution, mirroring LauncherActivity's routes
    // without needing the activity.
    private fun appForTarget(viewModel: LauncherViewModel, target: String): com.supernova.launcher.data.LaunchableApp? {
        val (body, serial) = com.supernova.launcher.data.LaunchTarget.split(target)
        val pkg = if (body.contains('|')) body.substringBefore('|') else body.substringBefore('/')
        val cls = if (body.contains('|')) "" else body.substringAfter('/', "")
        if (pkg.isEmpty()) return null
        return viewModel.state.value.apps.firstOrNull {
            it.packageName == pkg && (cls.isEmpty() || it.className == cls) && it.userSerial == serial
        } ?: com.supernova.launcher.data.LaunchableApp(pkg, cls, "", serial)
    }

    internal fun iconForTarget(viewModel: LauncherViewModel, target: String): android.graphics.drawable.Drawable? {
        val app = appForTarget(viewModel, target) ?: return null
        val body = com.supernova.launcher.data.LaunchTarget.split(target).first
        if (body.contains('|')) {
            val id = body.substringAfter('|')
            viewModel.shortcutsFor(app.packageName, app.userSerial).firstOrNull { it.id == id }
                ?.let { shortcut -> viewModel.appCatalog().shortcutIcon(shortcut)?.let { return it } }
        }
        return viewModel.appCatalog().iconFor(app)
    }

    internal fun labelForTarget(viewModel: LauncherViewModel, target: String): String? {
        val app = appForTarget(viewModel, target) ?: return null
        val appLabel = app.label.takeIf { it.isNotBlank() }
            ?: viewModel.state.value.apps.firstOrNull {
                it.packageName == app.packageName && it.userSerial == app.userSerial
            }?.label
        val body = com.supernova.launcher.data.LaunchTarget.split(target).first
        if (!body.contains('|')) return appLabel
        val id = body.substringAfter('|')
        val shortcut = viewModel.shortcutsFor(app.packageName, app.userSerial).firstOrNull { it.id == id }
        val shortcutLabel = (shortcut?.shortLabel ?: shortcut?.longLabel)?.toString()
        return when {
            shortcutLabel != null && appLabel != null -> "$appLabel: $shortcutLabel"
            else -> shortcutLabel ?: appLabel
        }
    }
}
