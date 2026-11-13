package com.supernova.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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


    // The search-bar menu hangs off a long press.
    var onLongPress: ((View) -> Unit)? = null

    // A host's own button kept at the bar's right end, after the chips (the
    // feed's overflow menu); null takes it out again. The host styles it.
    var trailing: View? = null
        set(value) {
            if (field === value) return
            field?.let { removeView(it) }
            field = value
            if (value != null) {
                (value.parent as? android.view.ViewGroup)?.removeView(value)
                // Sized like a chip: its icon in the magnifier's 20dp box.
                (value as? ImageView)?.scaleType = ImageView.ScaleType.FIT_CENTER
                value.setPadding(dp(CHIP_PAD_DP), dp(CHIP_PAD_DP), dp(CHIP_PAD_DP), dp(CHIP_PAD_DP))
                addView(value, LayoutParams(dp(CHIP_DP), dp(CHIP_DP)).apply { marginStart = dp(2) })
            }
        }

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
        hint.textSizeOf(R.dimen.text_body)
        hint.maxLines = 1
        addView(hint, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })

    }

    private fun addChip(chip: SearchBarChip, color: Int) {
        val view = SearchBarButtons.view(context, chip, color) {
            if (interactive) SearchBarButtons.run(context, chip)
        }
        view.tag = CHIP_TAG
        val size = dp(SearchBarButtons.BUTTON_DP)
        val width = if (chip == SearchBarChip.DATE) LayoutParams.WRAP_CONTENT else size
        addView(view, chipIndex, LayoutParams(width, size).apply { marginStart = dp(2) })
        if (chip == SearchBarChip.DATE) dateChip = view as TextView
    }

    // Chips go before the host's trailing button, if there is one.
    private val chipIndex: Int get() = trailing?.let(::indexOfChild)?.takeIf { it >= 0 } ?: childCount

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

            // The magnifier on the left is fixed (set in init), in the
            // bar's text colour like its buttons.
            val tint = ColorStateList.valueOf(resolved.text)
            ImageViewCompat.setImageTintList(searchIcon, tint)
            hint.setTextColor((0x99 shl 24) or (resolved.text and 0xFFFFFF))
            // Hidden, not gone: it still holds the chips to the right edge.
            hint.visibility = if (config.hint) VISIBLE else INVISIBLE

            // Chips rebuild in config order (C8); the host's trailing button
            // stays put at the end.
            for (index in childCount - 1 downTo 0) {
                if (getChildAt(index).tag == CHIP_TAG) removeViewAt(index)
            }
            dateChip = null
            config.chips.mapNotNull(SearchBarChip::fromName).distinct().forEach { chip ->
                addChip(chip, resolved.text)
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
        val text = SearchBarButtons.dateText(context)
        if (chip.text != text) chip.text = text
    }

    private companion object {
        const val CHIP_TAG = "search-bar-chip"
        const val CHIP_DP = SearchBarButtons.BUTTON_DP
        // Leaves the 20dp box the magnifier on the left has.
        const val CHIP_PAD_DP = 8
    }
}
