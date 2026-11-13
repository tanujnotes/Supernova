package com.supernova.launcher.onboarding

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.supernova.launcher.data.ContainerType
import com.supernova.launcher.data.ItemType
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.data.LauncherModel
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.data.WorkspaceGeometry
import com.supernova.launcher.ui.DockBgStyle
import com.supernova.launcher.ui.FadingTextView
import com.supernova.launcher.ui.LabelContext
import com.supernova.launcher.ui.LabelStyler
import com.supernova.launcher.ui.SearchBarConfig
import com.supernova.launcher.ui.SearchBarPlacement
import com.supernova.launcher.ui.SearchBarView
import com.supernova.launcher.ui.theme.StyleConfig
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import kotlin.math.roundToInt

// The first run's live preview: the home screen's first page and its dock,
// laid out at the screen's own size from the settings and the layout as
// they are now, with the launcher's own icons, labels and search bar. The
// wallpaper isn't drawn: the pane this sits in shows the real one through
// (PreviewPane). Its parent scales it down to fit, or up close to the dock
// for the steps about icons (see camera).
class HomePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // The home screen this stands for, in pixels, and the system bars over it.
    var homeWidth = 0
    var homeHeight = 0
    var topInset = 0
    var bottomInset = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(homeWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(homeHeight, MeasureSpec.EXACTLY)
        )
    }

    // Rebuilds the whole page: binds are rare (a choice tapped) and the
    // page is a dozen views. `icons` holds each item's icon by its id.
    fun bind(prefs: LauncherPrefs, model: LauncherModel, icons: Map<String, Drawable>) {
        removeAllViews()
        if (homeWidth <= 0 || homeHeight <= 0) return
        val chrome = chromeFor(prefs)
        val gridTop = topInset + dp(GRID_TOP_DP)
        val gridBottom = chrome.top - dp(GRID_GAP_DP)
        val sidePad = dp(SIDE_PAD_DP)
        val cols = prefs.gridCols.coerceAtLeast(1)
        val rows = prefs.gridRows.coerceAtLeast(1)
        val cellW = (homeWidth - 2 * sidePad) / cols.toFloat()
        val cellH = (gridBottom - gridTop) / rows.toFloat()

        // Placed as the home screen places them: an item off this grid
        // takes the cell it would be fitted to there.
        WorkspaceGeometry.fitted(model, cols, rows).items
            .filter { it.container == ContainerType.WORKSPACE && it.page == 0 }
            .forEach { item ->
                val rect = WorkspaceGeometry.itemRect(item, cols, rows)
                val view = if (item.itemType == ItemType.WIDGET) {
                    widgetPlate()
                } else {
                    iconColumn(item, icons[item.id], prefs, cellW, cellH, prefs.showLabels, LabelContext.DESKTOP)
                }
                addAt(
                    view, sidePad + rect.left * cellW, gridTop + rect.top * cellH,
                    (rect.right - rect.left) * cellW, (rect.bottom - rect.top) * cellH
                )
            }

        chrome.dockTop?.let { dockTop -> addDock(prefs, model, icons, dockTop, chrome.dockHeight) }
        chrome.barTop?.let { barTop -> addBar(prefs, barTop) }
    }

    // Where the dock and the search bar sit, from the bottom up, as the home
    // screen stacks them.
    private class Chrome(val top: Int, val dockTop: Int?, val dockHeight: Int, val barTop: Int?)

    private fun chromeFor(prefs: LauncherPrefs): Chrome {
        val bottom = homeHeight - bottomInset - dp(BOTTOM_GAP_DP)
        val dockHeight = (dp(ICON_DP) * prefs.dockIconScale).roundToInt() + dp(DOCK_PAD_DP) * 2
        val barHeight = dp(BAR_HEIGHT_DP)
        val gap = dp(BAR_GAP_DP)
        val placement = SearchBarPlacement.fromName(SearchBarConfig.decode(prefs.searchBarConfig).desktop.placement)
        val dock = prefs.dockEnabled
        return when {
            dock && (placement == SearchBarPlacement.DOCK_BELOW || placement == SearchBarPlacement.PERSISTENT) -> {
                val barTop = bottom - barHeight
                val dockTop = barTop - gap - dockHeight
                Chrome(dockTop, dockTop, dockHeight, barTop)
            }
            placement == SearchBarPlacement.DOCK_ABOVE && dock -> {
                val dockTop = bottom - dockHeight
                val barTop = dockTop - gap - barHeight
                Chrome(barTop, dockTop, dockHeight, barTop)
            }
            placement == SearchBarPlacement.PERSISTENT -> Chrome(bottom - barHeight, null, 0, bottom - barHeight)
            dock -> Chrome(bottom - dockHeight, bottom - dockHeight, dockHeight, null)
            else -> Chrome(bottom, null, 0, null)
        }
    }

    private fun addDock(
        prefs: LauncherPrefs,
        model: LauncherModel,
        icons: Map<String, Drawable>,
        top: Int,
        height: Int
    ) {
        val side = dp(DOCK_SIDE_DP)
        val width = homeWidth - 2 * side
        if (prefs.dockBgStyle != DockBgStyle.NONE) {
            val surface = Styles.resolve(context, prefs).of(SurfaceId.DOCK)
            val alpha = StyleConfig.decode(prefs.styleConfig).styleFor(SurfaceId.DOCK).alpha ?: prefs.dockBgOpacity
            val radius = when (prefs.dockBgStyle) {
                DockBgStyle.PILL -> height / 2f
                DockBgStyle.ROUNDED -> dp(DOCK_ROUNDED_DP).toFloat()
                else -> 0f
            }
            val plate = View(context)
            plate.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(((alpha.coerceIn(0f, 1f) * OPAQUE).roundToInt() shl ALPHA_SHIFT) or (surface.surface and RGB))
            }
            addAt(plate, side.toFloat(), top.toFloat(), width.toFloat(), height.toFloat())
        }
        val count = prefs.dockCount.coerceAtLeast(1)
        val slot = width / count.toFloat()
        model.items
            .filter { it.container == ContainerType.DOCK && it.rank in 0 until count }
            .forEach { item ->
                val view = iconColumn(
                    item, icons[item.id], prefs, slot, height.toFloat(), prefs.dockLabels, LabelContext.DOCK
                )
                addAt(view, side + item.rank * slot, top.toFloat(), slot, height.toFloat())
            }
    }

    private fun addBar(prefs: LauncherPrefs, top: Int) {
        val bar = SearchBarView(context).apply {
            interactive = false
            isClickable = false
            isLongClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val side = dp(BAR_SIDE_DP)
        addAt(bar, side.toFloat(), top.toFloat(), (homeWidth - 2 * side).toFloat(), dp(BAR_HEIGHT_DP).toFloat())
        val surface = Styles.resolve(context, prefs).of(SurfaceId.SEARCH_BAR)
        bar.apply(SearchBarConfig.decode(prefs.searchBarConfig).desktop, surface)
    }

    // An icon and its label, as item_icon.xml builds one: the icon at the
    // icon size, shrunk to fit its cell, and the label under it.
    @Suppress("LongParameterList")
    private fun iconColumn(
        item: LauncherItem,
        icon: Drawable?,
        prefs: LauncherPrefs,
        cellW: Float,
        cellH: Float,
        labels: Boolean,
        labelContext: LabelContext
    ): View {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        column.gravity = Gravity.CENTER
        val labelRoom = if (labels) dp(LABEL_ROOM_DP) else 0
        val scale = if (labelContext == LabelContext.DOCK) prefs.dockIconScale else prefs.iconScale
        val size = minOf(dp(ICON_DP) * scale, cellW - dp(ICON_INSET_DP), cellH - labelRoom - dp(ICON_INSET_DP))
            .roundToInt().coerceAtLeast(1)
        val image = ImageView(context)
        if (icon != null) {
            image.setImageDrawable(icon)
        } else {
            // A folder, or an app this phone can't draw: a plain plate.
            image.setImageDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(PLACEHOLDER)
            })
        }
        column.addView(image, LinearLayout.LayoutParams(size, size))
        if (labels) {
            val label = FadingTextView(context)
            label.text = item.label
            label.maxLines = 1
            label.gravity = Gravity.CENTER_HORIZONTAL
            label.setPadding(dp(2), dp(LABEL_GAP_DP), dp(2), 0)
            LabelStyler.apply(label, prefs, onWallpaper = true, context = labelContext)
            column.addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        return column
    }

    private fun widgetPlate(): View = View(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(WIDGET_RADIUS_DP).toFloat()
            setColor(PLACEHOLDER)
        }
    }

    private fun addAt(view: View, left: Float, top: Float, width: Float, height: Float) {
        addView(view, LayoutParams(width.roundToInt(), height.roundToInt()).apply {
            leftMargin = left.roundToInt()
            topMargin = top.roundToInt()
        })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val ICON_DP = 48
        const val ICON_INSET_DP = 8
        const val LABEL_ROOM_DP = 22
        const val LABEL_GAP_DP = 4
        const val GRID_TOP_DP = 8
        const val GRID_GAP_DP = 8
        const val SIDE_PAD_DP = 8
        const val BOTTOM_GAP_DP = 8
        const val DOCK_PAD_DP = 16
        const val DOCK_SIDE_DP = 10
        const val DOCK_ROUNDED_DP = 22
        const val BAR_HEIGHT_DP = 48
        const val BAR_GAP_DP = 8
        const val BAR_SIDE_DP = 16
        const val WIDGET_RADIUS_DP = 16
        const val PLACEHOLDER = 0x40FFFFFF
        const val OPAQUE = 255f
        const val ALPHA_SHIFT = 24
        const val RGB = 0xFFFFFF
    }
}
