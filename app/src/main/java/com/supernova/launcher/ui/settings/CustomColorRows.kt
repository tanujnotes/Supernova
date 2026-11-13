package com.supernova.launcher.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.StringRes
import com.supernova.launcher.R
import com.supernova.launcher.ui.dp
import java.util.Locale

// Any color: Hue, Saturation and Brightness sliders on tracks that show
// where each one goes, and a hex field (RRGGBB, or AARRGGBB to set the
// opacity too). The HSV values are kept here so a slider never jumps as
// the color rounds through RGB.
class CustomColorRows(
    private val context: Context,
    private val container: LinearLayout,
    // A color from the sliders or the field: opaque RGB, and an alpha (0-255)
    // when the hex had one.
    private val onColor: (rgb: Int, alpha: Int?) -> Unit
) {
    private val hsv = FloatArray(HSV_SIZE)
    private val hue = SliderRow(R.string.color_hue, HUE_STEPS)
    private val saturation = SliderRow(R.string.color_saturation, LEVEL_STEPS)
    private val brightness = SliderRow(R.string.color_brightness, LEVEL_STEPS)
    private val hex = EditText(context)
    private var updating = false

    init {
        container.visibility = View.VISIBLE
        container.addView(TextView(context).apply {
            setText(R.string.color_custom)
            setTextColor(context.getColor(R.color.textSecondary))
            textSize = LABEL_SP
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(LABEL_TOP_DP) })
        hue.track.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            IntArray(HUE_STOPS) { Color.HSVToColor(floatArrayOf(it * FULL_HUE / (HUE_STOPS - 1), 1f, 1f)) }
        ).apply { cornerRadius = context.dp(TRACK_DP).toFloat() }
        listOf(hue, saturation, brightness).forEach { it.attach() }
        addHexRow()
    }

    // Shows a color picked elsewhere (a swatch, Default, the stored value).
    fun show(rgb: Int) {
        Color.colorToHSV(rgb, hsv)
        render(rgb)
    }

    private fun render(rgb: Int) {
        updating = true
        hue.seek.progress = Math.round(hsv[0])
        saturation.seek.progress = Math.round(hsv[1] * LEVEL_STEPS)
        brightness.seek.progress = Math.round(hsv[2] * LEVEL_STEPS)
        saturation.track.background = gradient(
            Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))
        )
        brightness.track.background = gradient(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
        if (!hex.hasFocus()) hex.setText(String.format(Locale.ROOT, "%06X", rgb and RGB_MASK))
        updating = false
    }

    private fun gradient(start: Int, end: Int) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(start, end))
            .apply { cornerRadius = context.dp(TRACK_DP).toFloat() }

    private fun fromSliders() {
        hsv[0] = hue.seek.progress.toFloat()
        hsv[1] = saturation.seek.progress / LEVEL_STEPS.toFloat()
        hsv[2] = brightness.seek.progress / LEVEL_STEPS.toFloat()
        val rgb = Color.HSVToColor(hsv)
        hex.clearFocus()
        render(rgb)
        onColor(rgb, null)
    }

    private fun addHexRow() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(rowName(R.string.color_hex), LinearLayout.LayoutParams(context.dp(NAME_DP), WRAP))
        hex.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(ARGB_DIGITS), InputFilter { source, start, end, _, _, _ ->
                val kept = source.subSequence(start, end).filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
                if (kept.length == end - start) null else kept
            })
            hint = context.getString(R.string.palette_seed_hint)
            setSingleLine()
            textSize = HEX_SP
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!updating) fromHex(s?.toString().orEmpty())
                }
            })
        }
        row.addView(hex, LinearLayout.LayoutParams(0, WRAP, 1f))
        container.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun fromHex(text: String) {
        val value = text.toLongOrNull(HEX_RADIX) ?: return
        val (rgb, alpha) = when (text.length) {
            RGB_DIGITS -> (value.toInt() or OPAQUE) to null
            ARGB_DIGITS -> (value.toInt() or OPAQUE) to (value.toInt() ushr ALPHA_SHIFT)
            else -> return
        }
        Color.colorToHSV(rgb, hsv)
        render(rgb)
        onColor(rgb, alpha)
    }

    private fun rowName(@StringRes name: Int) = TextView(context).apply {
        setText(name)
        setTextColor(context.getColor(R.color.textPrimary))
        textSize = NAME_SP
    }

    // A name, then the slider over its gradient track.
    private inner class SliderRow(@StringRes private val name: Int, max: Int) {
        val track = View(context)
        val seek = SeekBar(context).apply {
            this.max = max
            progressDrawable = ColorDrawable(Color.TRANSPARENT)
            setPadding(context.dp(THUMB_DP), 0, context.dp(THUMB_DP), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updating) fromSliders()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }

        fun attach() {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(rowName(name), LinearLayout.LayoutParams(context.dp(NAME_DP), WRAP))
            val frame = FrameLayout(context)
            frame.addView(track, FrameLayout.LayoutParams(MATCH, context.dp(TRACK_DP)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = context.dp(THUMB_DP)
                marginEnd = context.dp(THUMB_DP)
            })
            frame.addView(seek, FrameLayout.LayoutParams(MATCH, context.dp(ROW_DP)))
            row.addView(frame, LinearLayout.LayoutParams(0, WRAP, 1f))
            container.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val HSV_SIZE = 3
        const val HUE_STEPS = 360
        const val FULL_HUE = 360f
        const val HUE_STOPS = 7
        const val LEVEL_STEPS = 100
        const val TRACK_DP = 8
        const val THUMB_DP = 12
        const val ROW_DP = 40
        const val NAME_DP = 96
        const val LABEL_TOP_DP = 14
        const val LABEL_SP = 13f
        const val NAME_SP = 15f
        const val HEX_SP = 15f
        const val HEX_RADIX = 16
        const val RGB_DIGITS = 6
        const val ARGB_DIGITS = 8
        const val RGB_MASK = 0xFFFFFF
        const val OPAQUE = 0xFF000000.toInt()
        const val ALPHA_SHIFT = 24
    }
}
