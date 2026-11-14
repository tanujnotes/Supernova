package com.supernova.launcher.ui

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.supernova.launcher.R
import com.supernova.launcher.data.AppOverride
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.databinding.SheetAppEditBinding
import com.supernova.launcher.ui.settings.EnumLabels
import com.supernova.launcher.ui.theme.ThemeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// What the Edit sheet holds until Save, and the parts of it that answer at
// once: the preview of the icon as it will look, the chips naming what
// differs from the default, the style switch, the row of shapes, and Save's
// count of what changed since the sheet opened.
internal class AppEditDraft(
    val binding: SheetAppEditBinding,
    private val scope: CoroutineScope,
    private val viewModel: LauncherViewModel,
    // The name with no edit, which "Renamed" is judged against.
    private val defaultLabel: String,
    saved: AppOverride
) {
    // The icon drawn with an edit; null when the item has none to preview.
    var preview: ((AppOverride) -> Drawable?)? = null

    var shape: String? = saved.iconShape
    var pack: String? = saved.iconPackPackage
    var icon: String? = saved.iconDrawableName
    var photo: String? = saved.iconBase64
    var variant: String? = saved.iconVariant

    private val context = binding.root.context
    private val prefs: LauncherPrefs = viewModel.state.value.prefs
    private val accent = ThemeEngine.accentColor(context, prefs.accentColor)
    private val swatches = mutableListOf<Pair<IconShape?, ShapeSwatchView>>()
    private var savedEdit: AppOverride? = null
    private var previewJob: Job? = null

    // The edit the sheet would save now. The name is the one typed, kept
    // only when it differs from the default.
    fun edit(): AppOverride {
        val typed = typedLabel()
        return AppOverride(
            customLabel = typed.takeIf { it.isNotEmpty() && it != defaultLabel },
            hideLabel = binding.hideLabelSwitch.isChecked,
            iconShape = shape,
            iconPackPackage = if (icon != null) pack else null,
            iconDrawableName = icon,
            iconBase64 = photo,
            iconVariant = variant
        )
    }

    fun typedLabel(): String = binding.editLabel.text?.toString().orEmpty().trim()

    // Wires the live parts; call once the name field and switch hold the
    // saved values.
    fun bind() {
        bindVariants()
        bindShapes()
        binding.editLabel.doAfterTextChanged { refresh() }
        binding.hideLabelSwitch.setOnCheckedChangeListener { _, _ -> refresh() }
        savedEdit = edit()
        refresh()
    }

    fun refresh() {
        binding.iconValue.text = photo?.let { context.getString(R.string.icon_photo) }
            ?: icon?.let { IconPickerSheet.pickLabel(viewModel, pack, it) }
            ?: context.getString(R.string.option_default)
        binding.shapeValue.text = shapeLabel(shape?.let(IconShape::fromName))
        binding.variantHint.setText(
            when (variant) {
                THEMED -> R.string.variant_themed_hint
                ORIGINAL -> R.string.variant_original_hint
                else -> R.string.variant_default_hint
            }
        )
        swatches.forEach { (option, view) ->
            view.isSelected = option?.name == shape
            view.fillColor = if (view.isSelected) accent else context.getColor(R.color.textSecondary)
            view.invalidate()
        }
        val hidden = binding.hideLabelSwitch.isChecked
        binding.previewLabel.text = typedLabel().ifEmpty { defaultLabel }
        binding.previewLabel.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        renderChips()
        renderSave()
        renderPreview()
    }

    private fun renderPreview() {
        val draw = preview ?: return
        val draft = edit()
        previewJob?.cancel()
        previewJob = scope.launch {
            val drawn = withContext(Dispatchers.Default) { draw(draft) }
            binding.previewIcon.setImageDrawable(drawn)
        }
    }

    // What differs from the default, one chip each; one muted chip when
    // nothing does.
    private fun renderChips() {
        val changes = buildList {
            val typed = typedLabel()
            if (typed.isNotEmpty() && typed != defaultLabel) add(context.getString(R.string.edit_change_renamed))
            if (binding.hideLabelSwitch.isChecked) add(context.getString(R.string.edit_change_hidden))
            binding.iconValue.text.takeIf { photo != null || icon != null }
                ?.let { add(context.getString(R.string.edit_change_icon, it)) }
            VARIANT_NAMES[variant]?.let { add(context.getString(it)) }
            shape?.let { add(shapeLabel(IconShape.fromName(it))) }
        }
        val group = binding.previewChanges
        group.removeAllViews()
        val texts = changes.ifEmpty { listOf(context.getString(R.string.edit_no_changes)) }
        texts.forEach { text ->
            group.addView(
                Chip(context).apply {
                    this.text = text
                    isClickable = false
                    isCheckable = false
                    isFocusable = false
                    setEnsureMinTouchTargetSize(false)
                    chipMinHeight = CHIP_HEIGHT_DP * resources.displayMetrics.density
                    textSize = CHIP_TEXT_SP
                    if (changes.isEmpty()) setTextColor(context.getColor(R.color.textSecondary))
                }
            )
        }
    }

    // Save counts what changed since the sheet opened: name, hidden name,
    // icon, style, shape.
    private fun renderSave() {
        val before = savedEdit ?: return
        val now = edit()
        val count = listOf(
            now.customLabel != before.customLabel,
            now.hideLabel != before.hideLabel,
            now.iconPackPackage != before.iconPackPackage || now.iconDrawableName != before.iconDrawableName ||
                now.iconBase64 != before.iconBase64,
            now.iconVariant != before.iconVariant,
            now.iconShape != before.iconShape
        ).count { it }
        binding.saveButton.text = if (count == 0) {
            context.getString(R.string.save)
        } else {
            context.resources.getQuantityString(R.plurals.save_changes, count, count)
        }
    }

    private fun bindVariants() {
        val ids = mapOf(null to R.id.variantDefault, THEMED to R.id.variantThemed, ORIGINAL to R.id.variantOriginal)
        binding.variantGroup.check(ids[variant] ?: R.id.variantDefault)
        binding.variantGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            variant = ids.entries.firstOrNull { it.value == id }?.key
            refresh()
        }
    }

    // Default first, drawn as an outline of the launcher's own shape, then
    // every shape.
    private fun bindShapes() {
        val custom = CustomShapeSpec.decode(prefs.iconShapeCustom)
        val size = (SWATCH_DP * context.resources.displayMetrics.density).toInt()
        val gap = (SWATCH_GAP_DP * context.resources.displayMetrics.density).toInt()
        (listOf<IconShape?>(null) + IconShape.entries).forEach { option ->
            val view = ShapeSwatchView(context).apply {
                shape = option ?: prefs.iconShape
                this.custom = custom
                outlined = option == null
                ringColor = accent
                contentDescription = shapeLabel(option)
                tooltipText = shapeLabel(option)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    this@AppEditDraft.shape = option?.name
                    refresh()
                }
            }
            val params = LinearLayout.LayoutParams(size, size).apply {
                marginStart = gap
                marginEnd = gap
            }
            binding.shapeSwatches.addView(view, params)
            swatches += option to view
        }
    }

    private fun shapeLabel(option: IconShape?): String =
        option?.let { context.getString(EnumLabels.of(it)) } ?: context.getString(R.string.edit_shape_default)

    private companion object {
        const val THEMED = "THEMED"
        const val ORIGINAL = "ORIGINAL"
        val VARIANT_NAMES = mapOf(THEMED to R.string.variant_themed, ORIGINAL to R.string.variant_original)
        const val SWATCH_DP = 52
        const val SWATCH_GAP_DP = 4
        const val CHIP_HEIGHT_DP = 28f
        const val CHIP_TEXT_SP = 12f
    }
}
