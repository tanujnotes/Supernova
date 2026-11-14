package com.supernova.launcher.onboarding

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.supernova.launcher.data.LauncherPrefs
import kotlin.math.roundToInt

// The first run's choice tiles and the grids they sit in. Each tile it
// makes is kept in `choices` with what makes it the chosen one, so a
// settings change only reselects the step's tiles.
internal class OnboardingTiles(private val context: Context) {

    class Choice(
        val view: View,
        val label: CharSequence,
        val isChosen: (LauncherPrefs) -> Boolean,
        val onChosen: (Boolean) -> Unit
    )

    val choices = mutableListOf<Choice>()

    // A choice: its picture over its name, on a plate that rings white while
    // it is the chosen one.
    @Suppress("LongParameterList")
    fun tile(
        label: CharSequence,
        content: View?,
        heightDp: Int,
        isChosen: (LauncherPrefs) -> Boolean,
        showLabel: Boolean = true,
        labelSp: Float = LABEL_SP,
        onChosen: (Boolean) -> Unit = {},
        onPick: () -> Unit
    ): View {
        val tile = LinearLayout(context)
        tile.orientation = LinearLayout.VERTICAL
        tile.gravity = Gravity.CENTER
        tile.minimumHeight = dp(heightDp)
        tile.background = tileBackground()
        tile.foreground = themeDrawable(android.R.attr.selectableItemBackground)
        makeButton(tile)
        if (content != null) tile.addView(content, matchWrap())
        if (showLabel) {
            tile.addView(TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = labelSp
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, wrap().apply { if (content != null) topMargin = dp(GAP_DP) })
        }
        tile.setOnClickListener { onPick() }
        choices += Choice(tile, label, isChosen, onChosen)
        return tile
    }

    // A round swatch with a ring around it while chosen.
    fun swatch(label: CharSequence, fill: Drawable, isChosen: (LauncherPrefs) -> Boolean, onPick: () -> Unit): View {
        val frame = FrameLayout(context)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(RING_DP), Color.WHITE)
        }
        frame.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), ring)
            addState(intArrayOf(), GradientDrawable())
        }
        val dot = View(context)
        dot.background = fill
        frame.addView(dot, FrameLayout.LayoutParams(dp(DOT_DP), dp(DOT_DP), Gravity.CENTER))
        frame.foreground = themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
        makeButton(frame)
        frame.setOnClickListener { onPick() }
        choices += Choice(frame, label, isChosen) {}
        val holder = FrameLayout(context)
        holder.addView(frame, FrameLayout.LayoutParams(dp(SWATCH_DP), dp(SWATCH_DP), Gravity.CENTER))
        holder.minimumHeight = dp(SWATCH_DP)
        return holder
    }

    // Equal columns, rows apart by the same gap; a short last row keeps
    // its tiles the others' width.
    fun grid(columns: Int, views: List<View>, gapDp: Int = GAP_DP): LinearLayout {
        val column = vertical()
        views.chunked(columns).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            for (index in 0 until columns) {
                val view = chunk.getOrNull(index) ?: Space(context)
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (index > 0) params.marginStart = dp(gapDp)
                row.addView(view, params)
            }
            column.addView(row, matchWrap().apply { if (rowIndex > 0) topMargin = dp(gapDp) })
        }
        return column
    }

    fun vertical(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun tileBackground(): Drawable {
        val radius = dp(TILE_RADIUS_DP).toFloat()
        return StateListDrawable().apply {
            val on = roundedPlate(TILE_ON, radius).apply { setStroke(dp(RING_DP), Color.WHITE) }
            addState(intArrayOf(android.R.attr.state_selected), on)
            addState(intArrayOf(), roundedPlate(TILE_OFF, radius))
        }
    }

    // Read out as a button, which is what it does.
    private fun makeButton(view: View) {
        view.isClickable = true
        view.isFocusable = true
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }

    private fun themeDrawable(attr: Int): Drawable? {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return null
        return AppCompatResources.getDrawable(context, value.resourceId)
    }

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        const val GAP_DP = 8
        private const val TILE_RADIUS_DP = 16
        private const val RING_DP = 2
        private const val TILE_ON = 0x2BFFFFFF
        private const val TILE_OFF = 0x12FFFFFF
        private const val LABEL_SP = 13f
        private const val SWATCH_DP = 52
        private const val DOT_DP = 40


    }
}

// A flat rounded rectangle: a tile's plate, the stand-in wallpaper.
internal fun roundedPlate(color: Int, radius: Float) = GradientDrawable().apply {
    cornerRadius = radius
    setColor(color)
}

// How each icon finish looks on the first run's own tiles, which can't
// render a themed icon or the wallpaper under glass.
internal object FinishSamples {

    // Tinted icons, roughly: the icon's light and dark mapped onto a light
    // and a dark shade of the accent. The preview above shows the real ones.
    fun duotone(accent: Int): ColorMatrixColorFilter {
        val dark = ColorUtils.blendARGB(accent, Color.BLACK, DUOTONE_DARK)
        val light = ColorUtils.blendARGB(accent, Color.WHITE, DUOTONE_LIGHT)
        fun row(shift: Int): FloatArray {
            val from = (dark shr shift) and BYTE
            val to = (light shr shift) and BYTE
            val k = (to - from) / BYTE_F
            return floatArrayOf(k * LUMA_R, k * LUMA_G, k * LUMA_B, 0f, from.toFloat())
        }
        val alpha = floatArrayOf(0f, 0f, 0f, 1f, 0f)
        return ColorMatrixColorFilter(ColorMatrix(row(RED_SHIFT) + row(GREEN_SHIFT) + row(0) + alpha))
    }

    // The look tiles' sample icons in a finish: the app colours as they
    // are, one tint for themed icons, or panes of glass.
    fun color(finish: OnboardingChoices.Finish, color: Int): Int = when (finish) {
        OnboardingChoices.Finish.ORIGINAL -> color
        OnboardingChoices.Finish.TINTED -> TINTED_SAMPLE
        OnboardingChoices.Finish.FROSTED ->
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(color, Color.WHITE, FROST_MIX), FROST_ALPHA)
        OnboardingChoices.Finish.CLEAR -> ColorUtils.setAlphaComponent(Color.WHITE, CLEAR_ALPHA)
        OnboardingChoices.Finish.SMOKED -> ColorUtils.setAlphaComponent(Color.BLACK, SMOKE_ALPHA)
    }

    private const val TINTED_SAMPLE = 0xFF3A4458.toInt()
    private const val FROST_MIX = 0.18f
    private const val FROST_ALPHA = 0x99
    private const val CLEAR_ALPHA = 0x38
    private const val SMOKE_ALPHA = 0x80
    private const val DUOTONE_DARK = 0.72f
    private const val DUOTONE_LIGHT = 0.55f
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f
    private const val BYTE = 0xFF
    private const val BYTE_F = 255f
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
