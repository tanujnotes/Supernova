package com.supernova.launcher.ui.settings

import android.app.Activity
import android.graphics.Outline
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.RadioGroup
import androidx.core.graphics.drawable.toBitmap
import com.google.android.material.radiobutton.MaterialRadioButton
import com.supernova.launcher.R
import com.supernova.launcher.databinding.SheetMotionChoiceBinding
import com.supernova.launcher.ui.LauncherViewModel
import com.supernova.launcher.ui.Sheets
import com.supernova.launcher.ui.settings.preview.LaunchPreviewView
import com.supernova.launcher.ui.settings.preview.MotionPreviewParts
import com.supernova.launcher.ui.settings.preview.PageEffectPreviewView

// The picker for a motion setting (#260): the option in force plays on a
// loop in a mini home screen above the list, and a tap on another option
// picks it and plays it there, the sheet staying up to try the next. A tap
// on the preview plays it again from the start.
object MotionChoiceSheet {

    // The catalogue's rows for a motion setting: as its plain choice rows,
    // but the pick opens as this sheet.
    class Rows(
        private val context: android.content.Context,
        private val viewModel: LauncherViewModel,
        private val pickChoice: (Choice) -> Unit
    ) {
        fun <T> choice(
            @androidx.annotation.StringRes title: Int,
            preview: ChoicePreview<T>,
            current: (com.supernova.launcher.data.LauncherPrefs) -> T,
            label: (T) -> Int,
            onPick: (T) -> Unit
        ): SettingItem.Row = SettingItem.Row(
            title,
            subtitle = { context.getString(label(current(it))) },
            onClick = {
                val values = preview.values
                val selected = values.indexOf(current(viewModel.state.value.prefs))
                val labels = values.map { context.getString(label(it)) }
                pickChoice(Choice(title, labels, selected, { onPick(values[it]) }, preview = preview))
            }
        )
    }

    private const val ROWS = 3
    private const val MIN_COLUMNS = 4
    private const val MAX_COLUMNS = 5
    private const val CORNER_DP = 20f
    private const val ROW_HEIGHT_DP = 48f
    private const val SAMPLE_SIZE = 64

    fun show(activity: Activity, viewModel: LauncherViewModel, choice: Choice) {
        val preview = choice.preview ?: return
        val binding = SheetMotionChoiceBinding.inflate(activity.layoutInflater)
        val sheet = Sheets.open(activity)
        val density = activity.resources.displayMetrics.density
        binding.motionTitle.setText(choice.title)

        val columns = viewModel.state.value.prefs.gridCols.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val icons = MotionPreviewParts.sampleIcons(activity, viewModel, columns * ROWS).map { it.second }
        val box = binding.motionPreview
        box.background = MotionPreviewParts.wallpaperBackground(activity, CORNER_DP * density)
        box.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, CORNER_DP * density)
        }
        box.clipToOutline = true

        // Which option to play: by index into the same order as the labels.
        val play: (Int) -> Unit
        val replay: () -> Unit
        when (preview) {
            is ChoicePreview.PageEffect -> {
                val view = PageEffectPreviewView(activity, icons, columns)
                box.addView(view, fill())
                play = { ChoicePreview.PageEffect.values.getOrNull(it)?.let(view::show) }
                replay = view::replay
            }
            is ChoicePreview.Launch -> {
                val color = icons.getOrNull(1)?.let { icon ->
                    val copy = icon.constantState?.newDrawable(activity.resources)?.mutate() ?: icon
                    viewModel.appCatalog().dominantColorOf(copy.toBitmap(SAMPLE_SIZE, SAMPLE_SIZE))
                } ?: activity.getColor(R.color.textSecondary)
                val view = LaunchPreviewView(activity, icons, columns, color)
                box.addView(view, fill())
                play = { ChoicePreview.Launch.values.getOrNull(it)?.let(view::show) }
                replay = view::replay
            }
        }
        box.setOnClickListener { replay() }

        val group = binding.motionOptions
        choice.labels.forEachIndexed { index, label ->
            val row = MaterialRadioButton(activity).apply {
                id = View.generateViewId()
                text = label
                setTextColor(activity.getColor(R.color.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, activity.resources.getDimension(R.dimen.text_body))
                minHeight = (ROW_HEIGHT_DP * density).toInt()
                isChecked = index == choice.selected
            }
            group.addView(
                row, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = (0 until group.childCount).firstOrNull { group.getChildAt(it).id == checkedId }
            if (index != null) {
                choice.onPick(index)
                play(index)
            }
        }
        play(choice.selected.coerceAtLeast(0))

        sheet.setContentView(binding.root)
        sheet.show()
    }

    private fun fill() =
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
}
