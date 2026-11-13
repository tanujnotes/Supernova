package com.supernova.launcher.ui.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import androidx.annotation.StringRes
import androidx.core.graphics.toColorInt
import com.supernova.launcher.R
import com.supernova.launcher.databinding.SheetStylePickerBinding
import com.supernova.launcher.ui.Sheets
import com.supernova.launcher.ui.dp
import com.supernova.launcher.ui.theme.ColorSpec
import com.supernova.launcher.ui.theme.SurfaceShapes
import com.supernova.launcher.ui.theme.TonalPalette
import java.util.Locale

// One color and its opacity for a setting stored as an ARGB string
// ("" = Default), with the style sheet's swatches and opacity slider.
// Every change goes to onPick at once; the caller decides when it is saved.
class ColorSheet(
    private val context: Context,
    private val palette: TonalPalette,
    private val defaultColor: Int
) {
    private val binding = SheetStylePickerBinding.inflate(LayoutInflater.from(context))
    private val defaultOpacity = percent(defaultColor)

    // The picked color, opaque (null = Default), and the opacity in percent.
    private var rgb: Int? = null
    private var opacity = defaultOpacity

    // Picking a color shows it solid, as the fixed list did, until the slider moves.
    private var opacityMoved = false
    private var onPick: (String) -> Unit = {}
    private val custom = CustomColorRows(context, binding.customRows) { picked, alpha ->
        rgb = picked
        when {
            alpha != null -> { opacity = percent(alpha shl ALPHA_SHIFT); opacityMoved = true }
            !opacityMoved -> opacity = FULL
        }
        onPick(value())
        render()
    }

    fun show(@StringRes title: Int, current: String, onPick: (String) -> Unit) {
        this.onPick = onPick
        val stored = current.takeIf { it.isNotEmpty() }?.let { runCatching { it.toColorInt() }.getOrNull() }
        if (stored != null) {
            rgb = stored or OPAQUE
            opacity = percent(stored)
            opacityMoved = opacity != FULL
        }
        binding.styleTitle.setText(title)
        listOf(binding.slotGroup, binding.stripText, binding.stripAccent, binding.contrastRow)
            .forEach { it.visibility = View.GONE }
        binding.styleDefault.setOnClickListener { pick(ColorSpec()) }
        binding.opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                opacity = progress
                opacityMoved = true
                binding.opacityValue.text = context.getString(R.string.opacity_percent, progress)
                renderStrip()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = onPick(value())
        })
        render()
        custom.show(rgb ?: defaultColor)
        val dialog = Sheets.open(context)
        dialog.setContentView(binding.root)
        dialog.show()
    }

    // Default at its own opacity is stored as "": a percent can't carry
    // every alpha back exactly.
    private fun isDefault() = rgb == null && opacity == defaultOpacity

    private fun color(): Int = if (isDefault()) {
        defaultColor
    } else {
        ((rgb ?: defaultColor) and OPAQUE.inv()) or (Math.round(opacity * MAX_ALPHA / FULL.toFloat()) shl ALPHA_SHIFT)
    }

    private fun value(): String = if (isDefault()) "" else String.format(Locale.ROOT, "#%08X", color())

    private fun specColor(spec: ColorSpec): Int? = when {
        spec.fixed != null -> spec.fixed.toColorInt() or OPAQUE
        spec.role != null && spec.tone != null -> palette.tone(spec.role, spec.tone) or OPAQUE
        else -> null
    }

    private fun pick(spec: ColorSpec) {
        rgb = specColor(spec)
        if (!opacityMoved) opacity = if (rgb == null) defaultOpacity else FULL
        onPick(value())
        render()
        custom.show(rgb ?: defaultColor)
    }

    private fun renderStrip() {
        binding.stripSurface.background = SurfaceShapes.background(color(), null, context.dp(STRIP_RADIUS_DP).toFloat())
    }

    private fun render() {
        renderStrip()
        binding.opacitySeek.progress = opacity
        binding.opacityValue.text = context.getString(R.string.opacity_percent, opacity)
        StylePickerSheet.fillSwatches(context, binding, palette, { rgb != null && specColor(it) == rgb }, ::pick)
    }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
        private const val FULL = 100
        private const val MAX_ALPHA = 255
        private const val ALPHA_SHIFT = 24
        private const val STRIP_RADIUS_DP = 14

        fun percent(color: Int): Int = (color ushr ALPHA_SHIFT) * FULL / MAX_ALPHA
    }
}
