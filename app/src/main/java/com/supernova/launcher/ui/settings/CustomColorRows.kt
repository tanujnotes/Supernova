package com.supernova.launcher.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import com.supernova.launcher.R
import com.supernova.launcher.databinding.ViewCustomColorBinding
import com.supernova.launcher.ui.DialogField
import com.supernova.launcher.ui.dp
import java.util.Locale

// Any color, under the Fixed colors row of the style and color sheets: a
// Color slider (hue), a Shade slider (dark to light in that hue), a hex
// field (RRGGBB, or AARRGGBB to set the opacity too) and the last few custom
// colors. Each change goes to onColor as it is made; the sheet's own strip
// is the preview.
class CustomColorRows(
    private val context: Context,
    private val container: ViewGroup,
    // Opaque RGB, and an alpha (0-255) when the hex had one.
    private val onColor: (rgb: Int, alpha: Int?) -> Unit
) {
    private val binding = ViewCustomColorBinding.inflate(LayoutInflater.from(context), container, true)
    private val hex = EditText(context)

    // Hue 0-360, saturation and lightness 0-1. Kept here so a slider never
    // jumps as the color rounds through RGB.
    private val hsl = FloatArray(HSL_SIZE)
    private var updating = false

    // The last color made here, kept for Recent when the sheet closes.
    private var made: Int? = null

    private val color: Int get() = ColorUtils.HSLToColor(hsl) or OPAQUE

    init {
        container.isVisible = true
        binding.customHueTrack.background = track(
            IntArray(HUE_STOPS) { Color.HSVToColor(floatArrayOf(it * FULL_HUE / (HUE_STOPS - 1), 1f, 1f)) }
        )
        binding.customShadeSeek.max = SHADE_MAX - SHADE_MIN
        listOf(binding.customHueSeek, binding.customShadeSeek).forEach { seek ->
            seek.progressDrawable = Color.TRANSPARENT.toDrawable()
            // A white knob that reads on any part of either track.
            seek.thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(context.dp(THUMB_RING_DP), THUMB_RING)
                setSize(context.dp(THUMB_DP), context.dp(THUMB_DP))
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updating) fromSliders(seekBar === binding.customHueSeek)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        addHexField()
        showRecent()
    }

    // Shows a color picked elsewhere (a swatch, Default, the stored value).
    fun show(rgb: Int) {
        ColorUtils.colorToHSL(rgb or OPAQUE, hsl)
        render()
    }

    // The sheet is closing: the last color made here joins Recent.
    fun remember() {
        val rgb = made ?: return
        val list = (listOf(rgb) + readRecent().filter { it != rgb }).take(RECENT_COUNT)
        runCatching {
            prefs.edit {
                putString(KEY_RECENT, list.joinToString(",") { String.format(Locale.ROOT, "%06X", it and RGB_MASK) })
            }
        }
    }

    private fun track(colors: IntArray) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
            .apply { cornerRadius = context.dp(TRACK_RADIUS_DP).toFloat() }

    private fun render() {
        val shadeAt = { lightness: Float -> ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], lightness)) }
        updating = true
        binding.customHueSeek.progress = Math.round(hsl[0]).coerceIn(0, MAX_HUE)
        binding.customShadeSeek.progress =
            (Math.round(hsl[2] * PERCENT) - SHADE_MIN).coerceIn(0, SHADE_MAX - SHADE_MIN)
        binding.customShadeTrack.background = track(
            intArrayOf(shadeAt(SHADE_MIN / PERCENT), shadeAt(HALF), shadeAt(SHADE_MAX / PERCENT))
        )
        if (!hex.hasFocus()) hex.setText(String.format(Locale.ROOT, "%06X", color and RGB_MASK))
        updating = false
    }

    private fun apply(alpha: Int?) {
        render()
        made = color
        onColor(color, alpha)
    }

    private fun fromSliders(hueMoved: Boolean) {
        hsl[0] = binding.customHueSeek.progress.toFloat()
        hsl[2] = (binding.customShadeSeek.progress + SHADE_MIN) / PERCENT
        // A grey has no hue to show: moving Color gives it some.
        if (hueMoved && hsl[1] < GREY) hsl[1] = PICKED_SATURATION
        hex.clearFocus()
        apply(null)
    }

    private fun addHexField() {
        hex.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(ARGB_DIGITS), InputFilter { source, start, end, _, _, _ ->
                val kept = source.subSequence(start, end).filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
                if (kept.length == end - start) null else kept
            })
            hint = context.getString(R.string.color_hex)
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!updating) fromHex(s?.toString().orEmpty())
                }
            })
        }
        // Flush with the sliders: the wrap brings a dialog's own sides.
        val field = DialogField.wrap(hex).apply { setPadding(0, paddingTop, 0, paddingBottom) }
        binding.customHexBox.addView(
            field,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun fromHex(text: String) {
        val value = text.toLongOrNull(HEX_RADIX) ?: return
        val alpha = when (text.length) {
            RGB_DIGITS -> null
            ARGB_DIGITS -> value.toInt() ushr ALPHA_SHIFT
            else -> return
        }
        ColorUtils.colorToHSL(value.toInt() or OPAQUE, hsl)
        apply(alpha)
    }

    // The last few custom colors, newest first; none yet hides the row.
    private fun showRecent() {
        val recent = readRecent()
        binding.customRecentLabel.isVisible = recent.isNotEmpty()
        binding.customRecentRow.isVisible = recent.isNotEmpty()
        val size = context.dp(SWATCH_DP)
        recent.forEach { rgb ->
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(rgb)
                    setStroke(context.dp(1), context.getColor(R.color.settingsDivider))
                }
                contentDescription = String.format(Locale.ROOT, "#%06X", rgb and RGB_MASK)
                setOnClickListener {
                    ColorUtils.colorToHSL(rgb, hsl)
                    hex.clearFocus()
                    apply(null)
                }
            }
            binding.customRecentRow.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                marginEnd = context.dp(SWATCH_GAP_DP)
            })
        }
    }

    private val prefs get() = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun readRecent(): List<Int> = runCatching {
        prefs.getString(KEY_RECENT, "").orEmpty().split(',').mapNotNull { entry ->
            entry.toLongOrNull(HEX_RADIX)?.toInt()?.or(OPAQUE)
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val HSL_SIZE = 3
        const val HUE_STOPS = 7
        const val FULL_HUE = 360f
        const val MAX_HUE = 359
        const val SHADE_MIN = 4
        const val SHADE_MAX = 96
        const val PERCENT = 100f
        const val HALF = 0.5f
        const val GREY = 0.08f
        const val PICKED_SATURATION = 0.55f
        const val TRACK_RADIUS_DP = 12
        const val THUMB_DP = 28
        const val THUMB_RING_DP = 3
        const val THUMB_RING = 0x66000000
        const val SWATCH_DP = 40
        const val SWATCH_GAP_DP = 10
        const val RECENT_COUNT = 5
        const val HEX_RADIX = 16
        const val RGB_DIGITS = 6
        const val ARGB_DIGITS = 8
        const val RGB_MASK = 0xFFFFFF
        const val OPAQUE = 0xFF000000.toInt()
        const val ALPHA_SHIFT = 24
        const val STATE_PREFS = "custom_color_state"
        const val KEY_RECENT = "recent"
    }
}
