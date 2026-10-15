package com.supernova.launcher.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.CalendarContract
import android.speech.RecognizerIntent
import android.text.format.DateUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.ImageViewCompat
import com.supernova.launcher.R
import com.supernova.launcher.data.IconGlass
import com.supernova.launcher.ui.theme.ResolvedSurface

// The desktop/dock search bar pill: SEARCH_BAR surface colors plus per-config
// radius, opacity, wireframe, shadow or glass, and optional action chips. The bar tap
// routes to the global overlay through onTap; chips blind-start their system
// intents directly (a missing handler just no-ops, like the web-search paths).
class SearchBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onTap: (() -> Unit)? = null

    // The settings editor embeds a live preview; chips must not fire real
    // intents from inside the dialog.
    var interactive = true

    private val pill = GradientDrawable()
    private val searchIcon = ImageView(context)
    private val hint = TextView(context)
    // Rebuilt from the config's chip list on every style change; the date
    // chip is remembered so midnight refreshes can find it.
    private var dateChip: TextView? = null
    private var applied: List<Any>? = null

    // APP_DRAWER chip: opening the drawer is the host's job.
    var onOpenDrawer: (() -> Unit)? = null

    // The search-bar menu hangs off a long press.
    var onLongPress: ((View) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(10), 0)
        background = pill
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.search_hint)
        setOnClickListener { onTap?.invoke() }
        setOnLongClickListener { onLongPress?.invoke(it); onLongPress != null }

        searchIcon.setImageResource(R.drawable.ic_search)
        searchIcon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(searchIcon, LayoutParams(dp(20), dp(20)))

        hint.setText(R.string.search_hint)
        hint.textSize = 15f
        hint.maxLines = 1
        addView(hint, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })

    }

    private fun addIconChip(iconRes: Int, labelRes: Int, tint: ColorStateList, onClick: () -> Unit) {
        val chip = ImageView(context)
        chip.setImageResource(iconRes)
        chip.setPadding(dp(8), dp(8), dp(8), dp(8))
        chip.background = borderlessRipple()
        chip.contentDescription = context.getString(labelRes)
        chip.setOnClickListener { if (interactive) onClick() }
        ImageViewCompat.setImageTintList(chip, tint)
        addView(chip, LayoutParams(dp(36), dp(36)).apply { marginStart = dp(2) })
    }

    private fun addDateChip(textColor: Int) {
        val chip = TextView(context)
        chip.textSize = 13f
        chip.maxLines = 1
        chip.gravity = Gravity.CENTER
        chip.setPadding(dp(6), dp(8), dp(6), dp(8))
        chip.background = borderlessRipple()
        chip.contentDescription = context.getString(R.string.search_bar_chip_date)
        chip.setOnClickListener { if (interactive) openCalendar() }
        chip.setTextColor(textColor)
        addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(2)
        })
        dateChip = chip
    }

    private fun borderlessRipple(): Drawable? {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        return if (value.resourceId != 0) {
            AppCompatResources.getDrawable(context, value.resourceId)
        } else {
            null
        }
    }

    // Re-styles the pill from config + resolved SEARCH_BAR surface; guarded so
    // repeated chrome passes with unchanged inputs cost nothing.
    fun apply(config: DesktopSearchBarConfig, resolved: ResolvedSurface) {
        // The accent too: a coloured logo wears it, and changing the bar's
        // accent in the Style tab left the old colour on the logo.
        val key = listOf(config, resolved.surface, resolved.text, resolved.accent)
        if (key != applied) {
            applied = key

            val radius = dp(config.radius.coerceIn(0, DesktopSearchBarConfig.MAX_RADIUS)).toFloat()
            val alpha = (config.alpha.coerceIn(0f, 1f) * 255).toInt()
            val glass = IconGlass.fromName(config.glass)
            if (glass != IconGlass.OFF) {
                // A see-through bar would show its own shadow through it, and
                // an outline-only one has no surface to be glass.
                background = GlassPillDrawable(glass, radius).also { it.alpha = alpha }
                elevation = 0f
            } else {
                pill.cornerRadius = radius
                if (config.wireframe) {
                    pill.setColor(Color.TRANSPARENT)
                    pill.setStroke(dp(2), (alpha shl 24) or (resolved.text and 0xFFFFFF))
                } else {
                    pill.setColor((alpha shl 24) or (resolved.surface and 0xFFFFFF))
                    pill.setStroke(0, 0)
                }
                if (background !== pill) background = pill
                elevation = if (config.shadow) dp(4).toFloat() else 0f
            }

            val tint = ColorStateList.valueOf(resolved.text)
            when (SearchBarLogo.fromName(config.logo)) {
                SearchBarLogo.SEARCH -> {
                    searchIcon.setImageResource(R.drawable.ic_search)
                    searchIcon.visibility = VISIBLE
                }
                SearchBarLogo.SUPERNOVA -> {
                    searchIcon.setImageResource(R.drawable.ic_supernova_logo)
                    searchIcon.visibility = VISIBLE
                }
                SearchBarLogo.NONE -> searchIcon.visibility = GONE
            }
            ImageViewCompat.setImageTintList(
                searchIcon,
                if (config.logoMonochrome) tint else ColorStateList.valueOf(resolved.accent)
            )
            hint.setTextColor((0x99 shl 24) or (resolved.text and 0xFFFFFF))
            // Hidden, not gone: it still holds the chips to the right edge.
            hint.visibility = if (config.hint) VISIBLE else INVISIBLE

            // Chips rebuild in config order (C8); everything after the icon
            // and hint is chip territory.
            while (childCount > 2) removeViewAt(childCount - 1)
            dateChip = null
            config.chips.mapNotNull(SearchBarChip::fromName).distinct().forEach { chip ->
                when (chip) {
                    SearchBarChip.VOICE ->
                        addIconChip(R.drawable.ic_mic, R.string.search_bar_chip_voice, tint) { startVoiceSearch() }
                    SearchBarChip.ASSISTANT ->
                        addIconChip(R.drawable.ic_assistant, R.string.search_bar_chip_assistant, tint) { startAssistant() }
                    SearchBarChip.PLAY_STORE ->
                        addIconChip(R.drawable.ic_play_store, R.string.chip_play_store, tint) { openPlayStore() }
                    SearchBarChip.APP_DRAWER ->
                        addIconChip(R.drawable.ic_action_drawer, R.string.action_open_drawer, tint) { onOpenDrawer?.invoke() }
                    SearchBarChip.DATE -> addDateChip(resolved.text)
                }
            }
        }
        refreshDate()
    }

    // The date chip must not go stale across midnight; window visibility is
    // the cheapest "home came back" signal.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) refreshDate()
    }

    private fun refreshDate() {
        val chip = dateChip ?: return
        val text = DateUtils.formatDateTime(
            context, System.currentTimeMillis(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
        )
        if (chip.text != text) chip.text = text
    }

    // Blind-start like the other chips: launches the store app when present.
    private fun openPlayStore() {
        runCatching {
            context.packageManager.getLaunchIntentForPackage("com.android.vending")
                ?.let(context::startActivity)
        }
    }

    private fun startVoiceSearch() {
        runCatching {
            context.startActivity(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
            )
        }
    }

    // Same ladder as the ASSISTANT gesture action, with the voice-command
    // front door first (the mic-style assistant surface where available).
    private fun startAssistant() {
        if (runCatching { context.startActivity(Intent(Intent.ACTION_VOICE_COMMAND)) }.isSuccess) return
        runCatching { context.startActivity(Intent(Intent.ACTION_ASSIST)) }
    }

    // The documented "open calendar at a day" deep link, blind-started: a
    // missing calendar app just no-ops.
    private fun openCalendar() {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .also { ContentUris.appendId(it, System.currentTimeMillis()) }
            .build()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

}
