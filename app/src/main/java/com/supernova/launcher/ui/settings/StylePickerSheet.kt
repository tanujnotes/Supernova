package com.supernova.launcher.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.supernova.launcher.R
import com.supernova.launcher.databinding.SheetStylePickerBinding
import com.supernova.launcher.data.CornerCut
import com.supernova.launcher.ui.dp
import com.supernova.launcher.ui.theme.SurfaceShapes
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.theme.AccentColor
import com.supernova.launcher.ui.theme.ColorSpec
import com.supernova.launcher.ui.theme.PaletteFactory
import com.supernova.launcher.ui.theme.PaletteRole
import com.supernova.launcher.ui.theme.PaletteSourceMode
import com.supernova.launcher.ui.theme.StyleConfig
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId
import com.supernova.launcher.ui.theme.ThemeEngine
import com.supernova.launcher.ui.theme.ToneRamp

// Per-surface style editor: Surface/Text/Accent color slots picked from the
// active tonal palette or fixed swatches, plus opacity and corner radius.
// Every change persists through prefs.styleConfig immediately; the strip at
// the top re-resolves from a prefs copy so it always shows the real result.
object StylePickerSheet {

    // Mirrors the curated list in SettingsActivity's color picker, minus the
    // Default entry - clearing a slot is the Default chip's job here.
    private val FIXED = listOf(
        "#FFFFFFFF", "#FF000000", "#FFE53935", "#FFFB8C00", "#FFFDD835",
        "#FF43A047", "#FF00ACC1", "#FF1E88E5", "#FF8E24AA"
    )

    private enum class Slot { SURFACE, TEXT, ACCENT }

    private val OPACITY_SURFACES = setOf(
        SurfaceId.DRAWER, SurfaceId.DOCK, SurfaceId.FOLDER, SurfaceId.SEARCH_BAR, SurfaceId.HOME
    )

    fun show(context: Context, viewModel: LauncherViewModel, surfaceId: SurfaceId) {
        val binding = SheetStylePickerBinding.inflate(LayoutInflater.from(context))
        val dialog = com.supernova.launcher.ui.Sheets.open(context)
        dialog.setContentView(binding.root)

        val prefs = viewModel.state.value.prefs
        val fallbackSeed = ThemeEngine.accentColor(context, AccentColor.fromName(prefs.accentColor))
        val palette = PaletteFactory.resolve(
            context, PaletteSourceMode.fromName(prefs.paletteSource), prefs.paletteSeed, fallbackSeed
        )
        val density = context.resources.displayMetrics.density

        var config = StyleConfig.decode(prefs.styleConfig)
        var style = config.styleFor(surfaceId)
        var slot = Slot.SURFACE

        binding.styleTitle.setText(EnumLabels.of(surfaceId))

        val stripDot = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        binding.stripAccent.background = stripDot

        fun persist() {
            config = config.with(surfaceId, style)
            viewModel.setStyleConfig(config.encode())
        }

        fun activeSpec(): ColorSpec = when (slot) {
            Slot.SURFACE -> style.surface
            Slot.TEXT -> style.text
            Slot.ACCENT -> style.accent
        }

        fun setActiveSpec(spec: ColorSpec) {
            style = when (slot) {
                Slot.SURFACE -> style.copy(surface = spec)
                Slot.TEXT -> style.copy(text = spec)
                Slot.ACCENT -> style.copy(accent = spec)
            }
        }

        fun renderStrip() {
            // Styles memoizes on the config string, so resolving a candidate
            // copy is how the strip previews the edit end to end.
            val resolved = Styles.resolve(
                context, prefs.copy(styleConfig = config.with(surfaceId, style).encode())
            ).of(surfaceId)
            binding.stripSurface.background = SurfaceShapes.background(
                resolved.surface,
                resolved.cornerRadiiDp?.let { dpArr -> FloatArray(4) { dpArr[it] * density } },
                resolved.cornerCuts,
                context.dp(14).toFloat()
            )
            binding.stripText.setTextColor(resolved.text)
            stripDot.setColor(resolved.accent)
        }

        fun swatch(color: Int, selected: Boolean, onClick: () -> Unit): ImageView {
            val view = ImageView(context)
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            if (selected) {
                view.setImageResource(R.drawable.ic_check)
                // Check contrast follows the swatch, not the theme.
                val bright = Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 > 160_000
                view.setColorFilter(if (bright) Color.BLACK else Color.WHITE)
                view.scaleType = ImageView.ScaleType.CENTER_INSIDE
                view.setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
            }
            view.setOnClickListener { onClick() }
            return view
        }

        fun rowLabel(textRes: Int): TextView = TextView(context).apply {
            setText(textRes)
            setTextColor(context.getColor(R.color.textSecondary))
            textSize = 13f
        }

        lateinit var renderSwatches: () -> Unit

        fun onPick(spec: ColorSpec) {
            setActiveSpec(spec)
            persist()
            renderStrip()
            renderSwatches()
            // The home background is transparent by default, and a color picked
            // without an opacity is drawn solid: the slider shows which.
            if (surfaceId == SurfaceId.HOME && style.alpha == null) {
                val shown = if (style.surface.isDefault) 0 else FULL_OPACITY
                binding.opacitySeek.progress = shown
                binding.opacityValue.text = context.getString(R.string.opacity_percent, shown)
            }
        }

        renderSwatches = {
            binding.swatchRows.removeAllViews()
            val spec = activeSpec()
            val swatchParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply {
                marginEnd = context.dp(8)
            }
            val labelParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(10) }

            fun addRow(labelRes: Int, fill: (LinearLayout) -> Unit) {
                binding.swatchRows.addView(rowLabel(labelRes), labelParams)
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, context.dp(6), 0, context.dp(2))
                }
                fill(row)
                val scroller = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(row)
                }
                binding.swatchRows.addView(
                    scroller,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            for (role in PaletteRole.entries) {
                val labelRes = when (role) {
                    PaletteRole.PRIMARY -> R.string.style_role_primary
                    PaletteRole.NEUTRAL -> R.string.style_role_neutral
                    PaletteRole.NEUTRAL_VARIANT -> R.string.style_role_neutral_variant
                }
                addRow(labelRes) { row ->
                    ToneRamp.TONES.forEachIndexed { index, tone ->
                        val selected = spec.fixed == null && spec.role == role &&
                            spec.tone != null && ToneRamp.indexOfTone(spec.tone) == index
                        row.addView(
                            swatch(palette.tone(role, tone), selected) {
                                onPick(ColorSpec(role = role, tone = tone))
                            },
                            swatchParams
                        )
                    }
                }
            }
            addRow(R.string.style_fixed_colors) { row ->
                FIXED.forEach { hex ->
                    val selected = hex.equals(spec.fixed, ignoreCase = true)
                    row.addView(
                        swatch(Color.parseColor(hex), selected) { onPick(ColorSpec(fixed = hex)) },
                        swatchParams
                    )
                }
            }
        }

        fun slotButtonId(target: Slot): Int = when (target) {
            Slot.SURFACE -> R.id.slotSurface
            Slot.TEXT -> R.id.slotText
            Slot.ACCENT -> R.id.slotAccent
        }

        fun renderSlotButtons() {
            val id = slotButtonId(slot)
            if (binding.slotGroup.checkedButtonId != id) binding.slotGroup.check(id)
        }

        // Minimum contrast: 0 to 60 tones in steps of 5, 0 being off.
        fun renderContrast() {
            binding.contrastRow.visibility =
                if (slot == Slot.TEXT) android.view.View.VISIBLE else android.view.View.GONE
            val value = style.minContrast ?: 0
            binding.contrastValue.text = if (value == 0) {
                context.getString(R.string.mode_off)
            } else {
                context.getString(R.string.style_min_contrast_value, value)
            }
            binding.contrastSeek.progress = value / CONTRAST_STEP
        }
        binding.contrastSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                style = style.copy(minContrast = (progress * CONTRAST_STEP).takeIf { it > 0 })
                persist()
                renderContrast()
                renderStrip()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })

        fun selectSlot(target: Slot) {
            slot = target
            renderSlotButtons()
            renderSwatches()
            renderContrast()
        }

        binding.slotGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val target = Slot.entries.first { slotButtonId(it) == checkedId }
            if (target != slot) selectSlot(target)
        }
        binding.styleDefault.setOnClickListener { onPick(ColorSpec()) }

        if (surfaceId in OPACITY_SURFACES) {
            // The resolved surface's alpha channel already folds style.alpha
            // over the legacy per-surface opacity default.
            val initial = (Styles.resolve(context, prefs).of(surfaceId).surface ushr 24) * 100 / 255
            binding.opacitySeek.progress = initial
            binding.opacityValue.text = context.getString(R.string.opacity_percent, initial)
            binding.opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    binding.opacityValue.text = context.getString(R.string.opacity_percent, progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    style = style.copy(alpha = seekBar.progress / 100f)
                    persist()
                    renderStrip()
                }
            })
        } else {
            binding.opacityRow.visibility = android.view.View.GONE
        }

        var radius: Int? = style.cornerRadii?.firstOrNull()?.toInt()
        fun renderRadius() {
            binding.radiusValue.text = radius?.let {
                context.getString(R.string.padding_dp_value, it)
            } ?: context.getString(R.string.option_default)
        }
        // All four corners move together for now; per-corner editing comes
        // later, which is why four values are stored.
        fun setRadius(value: Int) {
            radius = value
            style = style.copy(cornerRadii = List(4) { value.toFloat() })
            persist()
            renderRadius()
            renderStrip()
        }
        binding.radiusDown.setOnClickListener {
            setRadius(((radius ?: RADIUS_START) - RADIUS_STEP).coerceIn(0, RADIUS_MAX))
        }
        binding.radiusUp.setOnClickListener {
            setRadius(((radius ?: (RADIUS_START - RADIUS_STEP)) + RADIUS_STEP).coerceIn(0, RADIUS_MAX))
        }

        // Corner shape (C1): one cut style applied to all four corners, like
        // the uniform radius above. Picking a cut with no radius set gives the
        // radius its starting value so the change is visible.
        val cutButtons = listOf(
            binding.cutRound to CornerCut.ROUND,
            binding.cutSquircle to CornerCut.SQUIRCLE,
            binding.cutCut to CornerCut.CUT
        )

        fun renderCuts() {
            val current = CornerCut.fromName(style.cornerCuts?.firstOrNull())
            val id = cutButtons.first { it.second == current }.first.id
            if (binding.cutGroup.checkedButtonId != id) binding.cutGroup.check(id)
        }

        binding.cutGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val cut = cutButtons.first { it.first.id == checkedId }.second
            if (cut == CornerCut.fromName(style.cornerCuts?.firstOrNull())) return@addOnButtonCheckedListener
            style = style.copy(
                cornerCuts = if (cut == CornerCut.ROUND) null else List(4) { cut.name }
            )
            if (radius == null && cut != CornerCut.ROUND) {
                radius = RADIUS_START
                style = style.copy(cornerRadii = List(4) { RADIUS_START.toFloat() })
                renderRadius()
            }
            persist()
            renderStrip()
        }

        if (surfaceId == SurfaceId.HOME) {
            // A background behind the icons: a color and how much of the
            // wallpaper it hides, and nothing that needs text or corners.
            listOf(
                binding.slotGroup, binding.stripText, binding.stripAccent, binding.contrastRow,
                binding.radiusDown.parent as android.view.View, binding.cornerShapeRow
            ).forEach { it.visibility = android.view.View.GONE }
        }
        renderCuts()
        renderRadius()
        renderSlotButtons()
        renderSwatches()
        renderContrast()
        renderStrip()
        dialog.show()
    }

    private const val CONTRAST_STEP = 5
    private const val RADIUS_START = 16
    private const val RADIUS_STEP = 4
    private const val RADIUS_MAX = 32
    private const val FULL_OPACITY = 100
}
