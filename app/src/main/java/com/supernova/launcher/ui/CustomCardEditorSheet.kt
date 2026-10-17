package com.supernova.launcher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R
import com.supernova.launcher.databinding.ItemCardActionBinding
import com.supernova.launcher.databinding.RowCardConditionBinding
import com.supernova.launcher.databinding.SheetCardEditorBinding
import com.supernova.launcher.feed.CardAction
import com.supernova.launcher.feed.CardCondition
import com.supernova.launcher.feed.ConditionKind
import com.supernova.launcher.feed.FeaturedType
import com.supernova.launcher.feed.FeedCard
import java.util.Collections
import java.util.UUID

// The "Card details" page as a sheet: toolbar (back, Save, overflow with
// Pin / Enable / Delete), the live card, its name, then Featured content
// (with the widget height stepper), reorderable Actions, and the Activate
// section with one row per condition. Pickers reuse the settings-activity
// conventions: plain AlertDialog app lists (the gesture LAUNCH_APP pattern)
// and the two-step app -> shortcut list (the gesture LAUNCH_SHORTCUT
// pattern). Only Save commits; back/dismiss changes nothing.
//
// The Widget choice hands off to the host (onPickWidget): the bind/configure
// flow is LauncherActivity's, so the draft is saved first and the editor
// closes; the feed shows the result.
object CustomCardEditorSheet {

    fun show(
        activity: AppCompatActivity,
        viewModel: LauncherViewModel,
        existing: FeedCard?,
        // Opens the widget picker for a Widget-featured card; the card is saved
        // first so the bound widget attaches to a persisted id.
        onPickWidget: ((cardId: String) -> Unit)? = null
    ) {
        val binding = SheetCardEditorBinding.inflate(LayoutInflater.from(activity))
        val dialog = com.supernova.launcher.ui.Sheets.open(activity)
        dialog.setContentView(binding.root)
        // Every way out goes through the back button (system or toolbar), so
        // unsaved changes can be caught there: no tap-outside or swipe-away.
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        // Working state, committed only on Save.
        var featuredType: String? = existing?.featuredType
        var featuredTarget: String? = existing?.featuredTarget
        var widgetHeightDp = existing?.widgetHeightDp ?: DEFAULT_WIDGET_HEIGHT_DP
        val actions = existing?.actions.orEmpty().take(FeedCard.MAX_ACTIONS).toMutableList()
        var dayTime: CardCondition? = existing?.conditions?.firstOrNull {
            ConditionKind.fromName(it.kind) == ConditionKind.DAY_TIME
        }
        var bluetooth: CardCondition? = existing?.conditions?.firstOrNull {
            ConditionKind.fromName(it.kind) == ConditionKind.BLUETOOTH
        }
        var pinned = existing?.pinned ?: false
        var enabled = existing?.enabled ?: true
        binding.cardActivate.isChecked = existing?.activationEnabled ?: true
        binding.cardName.setText(existing?.name.orEmpty())

        // Live preview: the draft rendered by the feed's own binder. Launch
        // and menu are inert here; icons and labels come from the catalog. A
        // widget shows as "not available".
        val previewBinder = CardViewBinder(
            CardViewBinder.Callbacks(
                onLaunchTarget = { _, _, _ -> },
                widgetView = { null },
                iconForTarget = { target -> iconForTarget(viewModel, target) },
                labelForTarget = { target -> labelForTarget(viewModel, target) },
                onMenu = null
            )
        )
        val popup = com.supernova.launcher.ui.theme.Styles
            .resolve(activity, viewModel.state.value.prefs)
            .of(com.supernova.launcher.ui.theme.SurfaceId.POPUP)
        previewBinder.setAppearance(popup.surface, popup.text, popup.accent)
        val previewBinding = previewBinder.inflate(binding.cardPreview)
        binding.cardPreview.addView(previewBinding.root)

        // Stable across Save and the Widget-pick path so both target one card.
        val cardId = existing?.id ?: UUID.randomUUID().toString()

        fun buildCard(): FeedCard {
            val widget = FeaturedType.fromName(featuredType) == FeaturedType.WIDGET
            return FeedCard(
                id = cardId,
                name = binding.cardName.text?.toString()?.trim().orEmpty(),
                featuredType = featuredType,
                featuredTarget = featuredTarget,
                appWidgetId = existing?.appWidgetId?.takeIf { widget },
                widgetProvider = existing?.widgetProvider?.takeIf { widget },
                actions = actions.take(FeedCard.MAX_ACTIONS),
                conditions = listOfNotNull(dayTime, bluetooth),
                enabled = enabled,
                pinned = pinned,
                widgetHeightDp = widgetHeightDp,
                activationEnabled = binding.cardActivate.isChecked
            )
        }
        val refreshPreview = { previewBinder.bind(previewBinding, buildCard()) }
        binding.cardName.doAfterTextChanged { refreshPreview() }
        // What the sheet opened with, normalised the same way; a draft that
        // still equals it can close without asking.
        val initial = buildCard()

        fun save() {
            val card = buildCard()
            // Contextual runtime requests for the condition signals the card
            // just asked for; results are read on the next feed-show, so no
            // callback plumbing is needed here.
            requestConditionPermissions(activity, card.conditions)
            viewModel.upsertFeedCard(card)
            dialog.dismiss()
        }

        // Back with unsaved changes asks: save them, or discard them. A tap
        // outside the question (or back again) keeps editing.
        fun attemptClose() {
            if (buildCard() == initial) {
                dialog.dismiss()
                return
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.card_unsaved_title)
                .setMessage(R.string.card_unsaved_message)
                .setPositiveButton(R.string.save) { _, _ -> save() }
                .setNegativeButton(R.string.card_discard) { _, _ -> dialog.dismiss() }
                .show()
        }
        dialog.onBackPressedDispatcher.addCallback(dialog) { attemptClose() }

        // ---- Toolbar: back, Save, overflow (Pin, Enable, Delete) ----
        binding.cardBack.setOnClickListener { attemptClose() }
        binding.cardEditorMenu.setOnClickListener { anchor ->
            val menu = PopupMenu(activity, anchor)
            menu.menu.add(0, 1, 0, if (pinned) R.string.card_menu_unpin else R.string.card_menu_pin)
            menu.menu.add(0, 2, 1, if (enabled) R.string.card_menu_disable else R.string.card_menu_enable)
            if (existing != null) menu.menu.add(0, 3, 2, R.string.card_delete)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> pinned = !pinned
                    2 -> enabled = !enabled
                    3 -> {
                        viewModel.removeFeedCard(existing!!.id)
                        dialog.dismiss()
                    }
                }
                true
            }
            menu.show()
        }
        binding.cardSave.setOnClickListener { save() }

        // ---- Featured content ----
        fun refreshFeatured() {
            refreshPreview()
            val type = FeaturedType.fromName(featuredType)
            val set = type == FeaturedType.WIDGET || (type != null && featuredTarget != null)
            binding.cardFeaturedAdd.visibility = if (set) View.GONE else View.VISIBLE
            binding.cardFeaturedRow.visibility = if (set) View.VISIBLE else View.GONE
            binding.cardWidgetHeightRow.visibility = if (type == FeaturedType.WIDGET) View.VISIBLE else View.GONE
            if (!set) return
            if (type == FeaturedType.WIDGET) {
                val info = existing?.appWidgetId?.let {
                    android.appwidget.AppWidgetManager.getInstance(activity).getAppWidgetInfo(it)
                }
                binding.cardFeaturedLabel.text = info?.loadLabel(activity.packageManager)
                    ?: activity.getString(R.string.card_widget_unavailable)
                binding.cardFeaturedIcon.setImageDrawable(info?.loadIcon(activity, 0))
            } else {
                val target = featuredTarget.orEmpty()
                binding.cardFeaturedLabel.text = labelForTarget(viewModel, target)
                    ?: com.supernova.launcher.data.LaunchTarget.split(target).first
                binding.cardFeaturedIcon.setImageDrawable(
                    iconForTarget(viewModel, target)?.constantState?.newDrawable()?.mutate()
                )
            }
        }

        fun pickFeatured() {
            val options = buildList {
                add(activity.getString(R.string.card_featured_app))
                add(activity.getString(R.string.card_featured_shortcut))
                if (onPickWidget != null) add(activity.getString(R.string.widget))
            }.toTypedArray()
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.card_featured)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> pickApp(activity, viewModel) { target, _ ->
                            featuredType = FeaturedType.APP.name
                            featuredTarget = target
                            refreshFeatured()
                        }
                        1 -> pickShortcut(activity, viewModel) { target, _ ->
                            featuredType = FeaturedType.SHORTCUT.name
                            featuredTarget = target
                            refreshFeatured()
                        }
                        2 -> {
                            // Persist the draft, then bind a widget into it;
                            // the editor closes and the feed shows the result.
                            viewModel.upsertFeedCard(buildCard())
                            onPickWidget?.invoke(cardId)
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        binding.cardFeaturedAdd.setOnClickListener { pickFeatured() }
        binding.cardFeaturedRow.setOnClickListener { pickFeatured() }
        binding.cardFeaturedClear.setOnClickListener {
            featuredType = null
            featuredTarget = null
            refreshFeatured()
        }

        // Widget height: a stepper slider.
        fun refreshHeight() {
            binding.cardWidgetHeightValue.text =
                activity.getString(R.string.card_widget_height_value, widgetHeightDp)
            binding.cardWidgetHeightSeek.progress = (widgetHeightDp - WIDGET_HEIGHT_MIN_DP) / WIDGET_HEIGHT_STEP_DP
            refreshPreview()
        }
        binding.cardWidgetHeightSeek.max = (WIDGET_HEIGHT_MAX_DP - WIDGET_HEIGHT_MIN_DP) / WIDGET_HEIGHT_STEP_DP
        binding.cardWidgetHeightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                widgetHeightDp = WIDGET_HEIGHT_MIN_DP + progress * WIDGET_HEIGHT_STEP_DP
                refreshHeight()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        binding.cardWidgetHeightMinus.setOnClickListener {
            widgetHeightDp = (widgetHeightDp - WIDGET_HEIGHT_STEP_DP).coerceAtLeast(WIDGET_HEIGHT_MIN_DP)
            refreshHeight()
        }
        binding.cardWidgetHeightPlus.setOnClickListener {
            widgetHeightDp = (widgetHeightDp + WIDGET_HEIGHT_STEP_DP).coerceAtMost(WIDGET_HEIGHT_MAX_DP)
            refreshHeight()
        }

        // ---- Actions: reorderable rows ----
        val actionsAdapter = ActionsAdapter(actions, { target -> iconForTarget(viewModel, target) }) {
            binding.cardAddAction.visibility =
                if (actions.size >= FeedCard.MAX_ACTIONS) View.GONE else View.VISIBLE
            refreshPreview()
        }
        binding.cardActionsList.layoutManager = LinearLayoutManager(activity)
        binding.cardActionsList.adapter = actionsAdapter
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                Collections.swap(actions, from, to)
                actionsAdapter.notifyItemMoved(from, to)
                refreshPreview()
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = false
        })
        touchHelper.attachToRecyclerView(binding.cardActionsList)
        actionsAdapter.onDragHandle = { holder -> touchHelper.startDrag(holder) }
        fun refreshActions() {
            actionsAdapter.notifyDataSetChanged()
            binding.cardAddAction.visibility =
                if (actions.size >= FeedCard.MAX_ACTIONS) View.GONE else View.VISIBLE
            refreshPreview()
        }
        binding.cardAddAction.setOnClickListener {
            if (actions.size >= FeedCard.MAX_ACTIONS) {
                Toast.makeText(activity, R.string.card_actions_full, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val options = arrayOf(
                activity.getString(R.string.card_featured_app),
                activity.getString(R.string.card_featured_shortcut)
            )
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.card_add_action)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> pickApp(activity, viewModel) { target, label ->
                            actions.add(CardAction(FeaturedType.APP.name, target, label))
                            refreshActions()
                        }
                        1 -> pickShortcut(activity, viewModel) { target, label ->
                            actions.add(CardAction(FeaturedType.SHORTCUT.name, target, label))
                            refreshActions()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        // ---- Activation conditions: a row each, "+" to add, "x" to drop ----
        // Material's picker rather than the platform dialog, so the time
        // picker wears the same plate as every other dialog in the app.
        fun pickTime(initialMinute: Int?, onPicked: (Int) -> Unit) {
            val initial = initialMinute ?: (9 * 60)
            val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                .setTimeFormat(
                    if (android.text.format.DateFormat.is24HourFormat(activity)) {
                        com.google.android.material.timepicker.TimeFormat.CLOCK_24H
                    } else {
                        com.google.android.material.timepicker.TimeFormat.CLOCK_12H
                    }
                )
                .setHour(initial / 60)
                .setMinute(initial % 60)
                .build()
            picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
            picker.show(activity.supportFragmentManager, "card_time")
        }

        lateinit var refreshConditions: () -> Unit

        fun editDayTime() {
            val days = dayTime?.days.orEmpty().toMutableSet()
            var start = dayTime?.startMinute
            var end = dayTime?.endMinute
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            }
            // Seven letter chips, the day picker.
            val daysRow = com.google.android.material.chip.ChipGroup(activity).apply {
                isSingleLine = true
                chipSpacingHorizontal = activity.dp(4)
            }
            DAY_LABELS.forEachIndexed { index, labelRes ->
                val day = index + 1
                daysRow.addView(
                    com.google.android.material.chip.Chip(
                        activity, null, com.google.android.material.R.attr.chipStyle
                    ).apply {
                        text = activity.getString(labelRes)
                        isCheckable = true
                        isCheckedIconVisible = false
                        // Seven across a dialog: trim to a letter's width (the
                        // default 48dp touch target alone would overflow).
                        setEnsureMinTouchTargetSize(false)
                        chipStartPadding = 0f
                        chipEndPadding = 0f
                        textStartPadding = activity.dp(9).toFloat()
                        textEndPadding = activity.dp(9).toFloat()
                        chipMinHeight = activity.dp(34).toFloat()
                        isChecked = day in days
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) days.add(day) else days.remove(day)
                        }
                    }
                )
            }
            content.addView(daysRow)
            val times = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val startButton = com.google.android.material.button.MaterialButton(
                activity, null, androidx.appcompat.R.attr.borderlessButtonStyle
            )
            val endButton = com.google.android.material.button.MaterialButton(
                activity, null, androidx.appcompat.R.attr.borderlessButtonStyle
            )
            fun refreshTimes() {
                startButton.text = start?.let(::formatMinute) ?: activity.getString(R.string.card_time_start)
                endButton.text = end?.let(::formatMinute) ?: activity.getString(R.string.card_time_end)
            }
            startButton.setOnClickListener {
                pickTime(start) {
                    start = it
                    if (end == null) end = it
                    refreshTimes()
                }
            }
            endButton.setOnClickListener {
                pickTime(end) {
                    end = it
                    if (start == null) start = it
                    refreshTimes()
                }
            }
            refreshTimes()
            times.addView(startButton)
            times.addView(endButton)
            content.addView(times)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.card_cond_daytime)
                .setView(content)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val timed = start != null && end != null
                    dayTime = if (days.isEmpty() && !timed) {
                        null
                    } else {
                        CardCondition(
                            kind = ConditionKind.DAY_TIME.name,
                            days = days.sorted().takeIf { it.isNotEmpty() },
                            startMinute = start.takeIf { timed },
                            endMinute = end.takeIf { timed }
                        )
                    }
                    refreshConditions()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        fun editText(
            titleRes: Int,
            hintRes: Int,
            current: String?,
            message: CharSequence?,
            onDone: (String) -> Unit
        ) {
            val input = EditText(activity).apply {
                setHint(hintRes)
                setText(current.orEmpty())
                maxLines = 1
                setSelection(text.length)
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(titleRes)
                .apply { if (message != null) setMessage(message) }
                .setView(com.supernova.launcher.ui.DialogField.wrap(input))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    onDone(input.text?.toString()?.trim().orEmpty())
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        fun editBluetooth() = editText(
            R.string.card_cond_bt, R.string.card_cond_bt_field, bluetooth?.btName, null
        ) { name ->
            bluetooth = name.takeIf { it.isNotEmpty() }
                ?.let { CardCondition(kind = ConditionKind.BLUETOOTH.name, btName = it) }
            refreshConditions()
        }

        fun bindCondition(
            row: RowCardConditionBinding,
            titleRes: Int,
            subtitle: CharSequence?,
            set: Boolean,
            onEdit: () -> Unit,
            onClear: () -> Unit
        ) {
            row.conditionTitle.setText(titleRes)
            row.conditionSubtitle.text = subtitle
            row.conditionSubtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            row.conditionButton.setImageResource(if (set) R.drawable.ic_menu_remove else R.drawable.ic_menu_add)
            row.conditionButton.contentDescription =
                activity.getString(if (set) R.string.card_cond_remove else R.string.card_cond_add)
            row.conditionButton.setOnClickListener { if (set) onClear() else onEdit() }
            row.root.setOnClickListener { onEdit() }
        }

        refreshConditions = {
            bindCondition(
                binding.cardCondDay, R.string.card_cond_daytime,
                dayTime?.let { dayTimeSummary(activity, it) },
                dayTime != null, ::editDayTime
            ) {
                dayTime = null
                refreshConditions()
            }
            bindCondition(
                binding.cardCondBt, R.string.card_cond_bt,
                bluetooth?.btName ?: activity.getString(R.string.card_cond_bt_hint),
                bluetooth != null, ::editBluetooth
            ) {
                bluetooth = null
                refreshConditions()
            }
        }

        refreshFeatured()
        refreshHeight()
        refreshActions()
        refreshConditions()
        dialog.show()
    }

    // An action row: icon, label, remove, and a handle that starts a
    // drag through the sheet's ItemTouchHelper.
    private class ActionsAdapter(
        private val actions: MutableList<CardAction>,
        private val iconFor: (String) -> android.graphics.drawable.Drawable?,
        private val onRemoved: () -> Unit
    ) : RecyclerView.Adapter<ActionsAdapter.Holder>() {

        var onDragHandle: ((RecyclerView.ViewHolder) -> Unit)? = null

        class Holder(val binding: ItemCardActionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder =
            Holder(ItemCardActionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = actions.size

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val action = actions[position]
            holder.binding.actionLabel.text = action.label.ifBlank {
                com.supernova.launcher.data.LaunchTarget.split(action.target).first
            }
            holder.binding.actionIcon.setImageDrawable(
                iconFor(action.target)?.constantState?.newDrawable()?.mutate()
            )
            holder.binding.actionRemove.setOnClickListener {
                val at = holder.bindingAdapterPosition
                if (at < 0) return@setOnClickListener
                actions.removeAt(at)
                notifyItemRemoved(at)
                onRemoved()
            }
            holder.binding.actionHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onDragHandle?.invoke(holder)
                false
            }
        }
    }

    // "09:00 – 17:00 on Mon, Tue" / "Every day" / "Sat, Sun".
    private fun dayTimeSummary(context: Context, condition: CardCondition): String {
        val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
        val days = condition.days?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { iso -> names[(iso % 7) + 1] }
            ?: context.getString(R.string.card_day_every)
        val start = condition.startMinute
        val end = condition.endMinute
        if (start == null || end == null) return days
        val times = context.getString(R.string.card_daytime_times, formatMinute(start), formatMinute(end))
        return context.getString(R.string.card_daytime_on, times, days)
    }

    private val DAY_LABELS = listOf(
        R.string.card_day_mon, R.string.card_day_tue, R.string.card_day_wed,
        R.string.card_day_thu, R.string.card_day_fri, R.string.card_day_sat,
        R.string.card_day_sun
    )
    private const val DEFAULT_WIDGET_HEIGHT_DP = 160
    private const val WIDGET_HEIGHT_MIN_DP = 60
    private const val WIDGET_HEIGHT_MAX_DP = 400
    private const val WIDGET_HEIGHT_STEP_DP = 10

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

    // "pkg/cls@userSerial", mirroring the gesture LAUNCH_APP payload.
    private fun pickApp(
        activity: AppCompatActivity,
        viewModel: LauncherViewModel,
        onPicked: (target: String, label: String) -> Unit
    ) {
        val apps = viewModel.visibleApps()
        if (apps.isEmpty()) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.card_featured_app)
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                val app = apps[which]
                onPicked("${app.packageName}/${app.className}@${app.userSerial}", app.label)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // "pkg|shortcutId@userSerial", mirroring the gesture LAUNCH_SHORTCUT
    // payload and its two-step app -> shortcut picker.
    private fun pickShortcut(
        activity: AppCompatActivity,
        viewModel: LauncherViewModel,
        onPicked: (target: String, label: String) -> Unit
    ) {
        val apps = viewModel.visibleApps()
        if (apps.isEmpty()) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pick_shortcut)
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                val app = apps[which]
                val shortcuts = viewModel.shortcutsFor(app.packageName, app.userSerial)
                if (shortcuts.isEmpty()) {
                    Toast.makeText(activity, R.string.no_shortcuts, Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                val names = shortcuts.map { (it.shortLabel ?: it.longLabel ?: it.id).toString() }
                MaterialAlertDialogBuilder(activity)
                    .setTitle(app.label)
                    .setItems(names.toTypedArray()) { _, index ->
                        onPicked(
                            "${app.packageName}|${shortcuts[index].id}@${app.userSerial}",
                            "${app.label}: ${names[index]}"
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestConditionPermissions(
        activity: AppCompatActivity,
        conditions: List<CardCondition>
    ) {
        val wanted = mutableListOf<String>()
        val hasBt = conditions.any { ConditionKind.fromName(it.kind) == ConditionKind.BLUETOOTH }
        if (hasBt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !granted(activity, Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (wanted.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, wanted.toTypedArray(), 0)
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun formatMinute(minute: Int): String =
        String.format(java.util.Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)

}
