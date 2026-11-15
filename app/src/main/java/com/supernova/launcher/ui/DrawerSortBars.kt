package com.supernova.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.Pref
import com.supernova.launcher.ui.settings.EnumLabels
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId

// The drawer's in-place sort picker: a sort icon at the end of the strip
// over the app list, which also carries the hairline under the top row. It
// opens the order menu and writes the same setting the App drawer page
// shows.
class DrawerSortBars(
    private val context: Context,
    private val divider: View,
    private val viewModel: LauncherViewModel
) {
    private val listButton = button()

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
        listButton.setOnClickListener { view -> pickListOrder(view) }
    }

    // topRow: the top row is showing; list: the list's button may show
    // (no query is filtering it).
    fun show(prefs: LauncherPrefs, topRow: Boolean, list: Boolean) {
        val buttons = prefs.drawerSortButtons
        listButton.contentDescription = context.getString(
            R.string.drawer_sort_apps_desc, context.getString(EnumLabels.of(prefs.drawerSort))
        )
        divider.visibility = if (topRow) View.VISIBLE else View.GONE
        listButton.visibility = if (list && buttons) View.VISIBLE else View.GONE
        listBar.visibility = if (topRow || (list && buttons)) View.VISIBLE else View.GONE
    }

    // The drawer's text colour, a step down as the unselected tabs are.
    fun applyColors(text: Int) {
        val color = androidx.core.graphics.ColorUtils.setAlphaComponent(text, ICON_ALPHA)
        listButton.imageTintList = ColorStateList.valueOf(color)
        listButton.background = RippleDrawable(
            ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(text, RIPPLE_ALPHA)),
            null,
            null
        ).apply { radius = context.dp(RIPPLE_RADIUS_DP) }
    }

    private fun pickListOrder(anchor: View) {
        val prefs = viewModel.state.value.prefs
        val orders = DrawerSort.entries
        ChoiceMenu.show(
            anchor, 0f, popupSurface(prefs), orders.map { context.getString(EnumLabels.of(it)) },
            selected = orders.indexOf(prefs.drawerSort), animate = Animations.enabled(prefs), alignToAnchorEnd = true
        ) { index -> viewModel.set(Pref.drawerSort, orders[index]) }
    }

    private fun popupSurface(prefs: LauncherPrefs) = Styles.resolve(context, prefs).of(SurfaceId.POPUP)

    // An icon-only button with a full touch target.
    private fun button(): ImageView = ImageView(context).apply {
        setImageDrawable(ContextCompat.getDrawable(context, R.drawable.glyph_sort))
        scaleType = ImageView.ScaleType.CENTER
        isClickable = true
        isFocusable = true
    }

    private fun buttonParams() = FrameLayout.LayoutParams(
        context.dp(BUTTON_DP),
        context.dp(BUTTON_DP),
        Gravity.END or Gravity.CENTER_VERTICAL
    ).apply { marginEnd = context.dp(BUTTON_MARGIN_END_DP) }

    private companion object {
        const val ICON_ALPHA = 0xB3
        const val RIPPLE_ALPHA = 0x33
        const val BUTTON_DP = 48
        const val RIPPLE_RADIUS_DP = 20
        // The glyph's bars end 3dp inside its 24dp box, so with the 12dp
        // padding they end 16dp from the edge, in line with the search bar.
        const val BUTTON_MARGIN_END_DP = 1
        // drawerFrequentDivider's size and margins in activity_launcher.
        const val DIVIDER_WIDTH_DP = 72
        const val DIVIDER_HEIGHT_DP = 2
        const val DIVIDER_MARGIN_DP = 10
    }
}
