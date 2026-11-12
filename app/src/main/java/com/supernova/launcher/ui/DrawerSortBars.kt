package com.supernova.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.Pref
import com.supernova.launcher.ui.settings.EnumLabels
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId

// The drawer's in-place sort pickers: a small "order" button at the end of
// a strip over the top row, and one at the end of the strip over the app
// list, which also carries the hairline under the top row. Each opens the
// order menu and writes the same setting the App drawer page shows.
class DrawerSortBars(
    private val context: Context,
    private val divider: View,
    private val viewModel: LauncherViewModel
) {
    private val topButton = button()
    private val listButton = button()

    // Over the top row.
    val topBar: FrameLayout = FrameLayout(context).apply {
        addView(topButton, buttonParams())
        visibility = View.GONE
    }

    // Over the app list: the hairline centred, the button at the end.
    val listBar: FrameLayout = FrameLayout(context).apply {
        (divider.parent as? android.view.ViewGroup)?.removeView(divider)
        val rule = FrameLayout.LayoutParams(context.dp(DIVIDER_WIDTH_DP), context.dp(DIVIDER_HEIGHT_DP), Gravity.CENTER)
        rule.topMargin = context.dp(DIVIDER_MARGIN_DP)
        rule.bottomMargin = context.dp(DIVIDER_MARGIN_DP)
        addView(divider, rule)
        addView(listButton, buttonParams())
        visibility = View.GONE
    }

    init {
        topButton.setOnClickListener { view -> pickTopRowOrder(view) }
        listButton.setOnClickListener { view -> pickListOrder(view) }
    }

    // topRow: the top row is showing; list: the list's button may show
    // (no query is filtering it).
    fun show(prefs: LauncherPrefs, topRow: Boolean, list: Boolean) {
        val buttons = prefs.drawerSortButtons
        topButton.text = context.getString(EnumLabels.of(prefs.drawerTopRowSort))
        topButton.contentDescription = context.getString(R.string.drawer_sort_top_row_desc, topButton.text)
        listButton.text = context.getString(EnumLabels.of(prefs.drawerSort))
        listButton.contentDescription = context.getString(R.string.drawer_sort_apps_desc, listButton.text)
        topBar.visibility = if (topRow && buttons) View.VISIBLE else View.GONE
        divider.visibility = if (topRow) View.VISIBLE else View.GONE
        listButton.visibility = if (list && buttons) View.VISIBLE else View.GONE
        listBar.visibility = if (topRow || (list && buttons)) View.VISIBLE else View.GONE
    }

    // The drawer's text colour, a step down as the unselected tabs are.
    fun applyColors(text: Int) {
        val color = androidx.core.graphics.ColorUtils.setAlphaComponent(text, TEXT_ALPHA)
        for (button in listOf(topButton, listButton)) {
            button.setTextColor(color)
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(button, ColorStateList.valueOf(color))
            button.background = RippleDrawable(
                ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(text, RIPPLE_ALPHA)),
                null,
                GradientDrawable().apply {
                    cornerRadius = context.dp(BUTTON_HEIGHT_DP).toFloat()
                    setColor(android.graphics.Color.WHITE)
                }
            )
        }
    }

    private fun pickListOrder(anchor: View) {
        val prefs = viewModel.state.value.prefs
        val orders = DrawerSort.entries
        ChoiceMenu.show(
            anchor, 0f, popupSurface(prefs), orders.map { context.getString(EnumLabels.of(it)) },
            selected = orders.indexOf(prefs.drawerSort), animate = Animations.enabled(prefs), alignToAnchorEnd = true
        ) { index -> viewModel.set(Pref.drawerSort, orders[index]) }
    }

    private fun pickTopRowOrder(anchor: View) {
        val prefs = viewModel.state.value.prefs
        val orders = TopRowSort.entries
        ChoiceMenu.show(
            anchor, 0f, popupSurface(prefs), orders.map { context.getString(EnumLabels.of(it)) },
            selected = orders.indexOf(prefs.drawerTopRowSort), animate = Animations.enabled(prefs),
            alignToAnchorEnd = true
        ) { index -> viewModel.set(Pref.drawerTopRowSort, orders[index]) }
    }

    private fun popupSurface(prefs: LauncherPrefs) = Styles.resolve(context, prefs).of(SurfaceId.POPUP)

    // A compact text button: the order's name and a small drop-down arrow.
    private fun button(): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSingleLine = true
        gravity = Gravity.CENTER_VERTICAL
        minHeight = context.dp(BUTTON_HEIGHT_DP)
        setPaddingRelative(context.dp(BUTTON_PAD_START_DP), 0, context.dp(BUTTON_PAD_END_DP), 0)
        val arrow = ContextCompat.getDrawable(context, R.drawable.ic_arrow_drop_down)?.mutate()
        arrow?.setBounds(0, 0, context.dp(ARROW_DP), context.dp(ARROW_DP))
        setCompoundDrawablesRelative(null, null, arrow, null)
    }

    private fun buttonParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.END or Gravity.CENTER_VERTICAL
    ).apply { marginEnd = context.dp(BUTTON_MARGIN_END_DP) }

    private companion object {
        const val TEXT_SP = 13f
        const val TEXT_ALPHA = 0xB3
        const val RIPPLE_ALPHA = 0x33
        const val BUTTON_HEIGHT_DP = 32
        const val BUTTON_PAD_START_DP = 12
        const val BUTTON_PAD_END_DP = 6
        const val BUTTON_MARGIN_END_DP = 8
        const val ARROW_DP = 20
        // drawerFrequentDivider's size and margins in activity_launcher.
        const val DIVIDER_WIDTH_DP = 72
        const val DIVIDER_HEIGHT_DP = 2
        const val DIVIDER_MARGIN_DP = 10
    }
}
