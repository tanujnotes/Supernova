package com.supernova.launcher.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import com.supernova.launcher.R
import com.supernova.launcher.databinding.SheetCustomColorBinding
import com.supernova.launcher.ui.DialogField
import com.supernova.launcher.ui.Sheets
import com.supernova.launcher.ui.dp
import java.util.Locale

// Any color, from the + swatch of the style and color sheets: a preview, a
// Color slider (hue), a Shade slider (dark to light in that hue), a hex field
// (RRGGBB, or AARRGGBB to set the opacity too) and the last few colors used.
// Nothing changes until Use color, which hands the color back.
class CustomColorSheet(private val context: Context) {

    private val binding = SheetCustomColorBinding.inflate(LayoutInflater.from(context))
    private val hex = EditText(context)

    // Hue 0-360, saturation and lightness 0-1. Kept here so a slider never
    // jumps as the color rounds through RGB.
    private val hsl = FloatArray(HSL_SIZE)
    private var pickedAlpha: Int? = null
    private var updating = false

    // subtitle: what the color is for ("App drawer · Background").
    fun show(subtitle: CharSequence, initial: Int, onUse: (rgb: Int, alpha: Int?) -> Unit) {
        binding.customSubtitle.text = subtitle
        binding.customSubtitle.isVisible = subtitle.isNotEmpty()
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
        ColorUtils.colorToHSL(initial or OPAQUE, hsl)
        render()
        showRecent()

        val dialog = Sheets.open(context)
        dialog.setContentView(binding.root)
        binding.customCancel.setOnClickListener { dialog.dismiss() }
        binding.customUse.setOnClickListener {
            val rgb = color
            remember(rgb)
            dialog.dismiss()
            onUse(rgb, pickedAlpha)
        }
        dialog.show()
    }

    private val color: Int get() = ColorUtils.HSLToColor(hsl) or OPAQUE

    private fun track(colors: IntArray) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
            .apply { cornerRadius = context.dp(TRACK_RADIUS_DP).toFloat() }

    private fun shadeAt(lightness: Float) =
        ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1], lightness))

    private fun render() {
        updating = true
        val rgb = color
        binding.customHueSeek.progress = Math.round(hsl[0]).coerceIn(0, MAX_HUE)
        binding.customShadeSeek.progress =
            (Math.round(hsl[2] * PERCENT) - SHADE_MIN).coerceIn(0, SHADE_MAX - SHADE_MIN)
        binding.customShadeTrack.background = track(
            intArrayOf(shadeAt(SHADE_MIN / PERCENT), shadeAt(HALF), shadeAt(SHADE_MAX / PERCENT))
        )
        binding.customPreview.background = GradientDrawable().apply {
            cornerRadius = context.dp(PREVIEW_RADIUS_DP).toFloat()
            setColor(rgb)
            setStroke(context.dp(1), context.getColor(R.color.settingsDivider))
        }
        val text = String.format(Locale.ROOT, "#%06X", rgb and RGB_MASK)
        binding.customPreviewHex.text = text
        val light = ColorUtils.calculateLuminance(rgb) > LIGHT
        binding.customPreviewHex.setTextColor(if (light) Color.BLACK else Color.WHITE)
        binding.customPreview.contentDescription = context.getString(R.string.a11y_custom_color_preview, text)
        if (!hex.hasFocus()) hex.setText(text.removePrefix("#"))
        updating = false
    }

    private fun fromSliders(hueMoved: Boolean) {
        hsl[0] = binding.customHueSeek.progress.toFloat()
        hsl[2] = (binding.customShadeSeek.progress + SHADE_MIN) / PERCENT
        // A grey has no hue to show: moving Color gives it some.
        if (hueMoved && hsl[1] < GREY) hsl[1] = PICKED_SATURATION
        pickedAlpha = null
        hex.clearFocus()
        render()
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
        val rgb = value.toInt() or OPAQUE
        pickedAlpha = when (text.length) {
            RGB_DIGITS -> null
            ARGB_DIGITS -> value.toInt() ushr ALPHA_SHIFT
            else -> return
        }
        ColorUtils.colorToHSL(rgb, hsl)
        render()
    }

    // The last few colors used, newest first; none yet hides the row.
    private fun showRecent() {
        val recent = readRecent()
        binding.customRecentLabel.isVisible = recent.isNotEmpty()
        binding.customRecentRow.isVisible = recent.isNotEmpty()
        val size = context.dp(SWATCH_DP)
        recent.forEach { rgb ->
            val name = String.format(Locale.ROOT, "#%06X", rgb and RGB_MASK)
            val dot = android.view.View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(rgb)
                    setStroke(context.dp(1), context.getColor(R.color.settingsDivider))
                }
                contentDescription = name
                setOnClickListener {
                    ColorUtils.colorToHSL(rgb, hsl)
                    pickedAlpha = null
                    hex.clearFocus()
                    render()
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

    private fun remember(rgb: Int) {
        val list = (listOf(rgb) + readRecent().filter { it != rgb }).take(RECENT_COUNT)
        runCatching {
            prefs.edit {
                putString(KEY_RECENT, list.joinToString(",") { String.format(Locale.ROOT, "%06X", it and RGB_MASK) })
            }
        }
    }

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
        const val LIGHT = 0.45
        const val TRACK_RADIUS_DP = 12
        const val THUMB_DP = 28
        const val THUMB_RING_DP = 3
        const val THUMB_RING = 0x66000000
        const val PREVIEW_RADIUS_DP = 20
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
