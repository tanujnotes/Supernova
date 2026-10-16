package com.supernova.launcher.ui.settings

import android.content.res.ColorStateList
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.CompoundButton
import android.widget.SeekBar
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.databinding.ItemSettingAccordionBinding
import com.supernova.launcher.databinding.ItemSettingCardBinding
import com.supernova.launcher.databinding.ItemSettingGroupLabelBinding
import com.supernova.launcher.databinding.ItemSettingGroupRowBinding
import com.supernova.launcher.databinding.ItemSettingHeaderBinding
import com.supernova.launcher.databinding.ItemSettingLabelGroupBinding
import com.supernova.launcher.databinding.ItemSettingPreviewBinding
import com.supernova.launcher.databinding.ItemSettingRowBinding
import com.supernova.launcher.databinding.ItemSettingSectionBinding
import com.supernova.launcher.databinding.ItemSettingSliderBinding
import com.supernova.launcher.databinding.ItemSettingStyleTargetBinding
import com.supernova.launcher.databinding.ItemSettingToggleBinding

// Renders SettingItems from the latest LauncherPrefs snapshot. Prefs updates
// rebind rows; controls the user is actively touching are left alone so a
// state emission can't clobber an in-progress gesture.
class SettingsAdapter(private val previews: PreviewController) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // The row a tap came from, reported before the row's own action runs.
    // Choice actions are plain () -> Unit and have no view of their own, so
    // this is how a settings menu knows what to open under.
    var onRowTapped: ((View) -> Unit)? = null

    private var items: List<SettingItem> = emptyList()
    private var visible: List<SettingItem> = emptyList()
    private var prefs = LauncherPrefs()
    // One-shot search-navigation cue, consumed by the next bind of the row
    // whose title matches; 0 = none.
    private var highlightTitle = 0

    fun setHighlight(@StringRes titleRes: Int) {
        highlightTitle = titleRes
    }

    // Accordions collapsed by default, remembered per adapter instance and
    // per page: sections repeat accordion titles (three have an Advanced),
    // and each keeps its own open or closed.
    private val expandedAccordions = mutableMapOf<Int, MutableSet<Int>>()

    // The page the items belong to (a section's title), set with them.
    private var page = 0

    // A grouped page (the root list and Style): its rows sit in rounded
    // cards, one per run between Headers, and each Header labels the card
    // under it. SettingsGroupDecoration draws the cards from these.
    var grouped = false
        private set

    fun isGroupCard(position: Int): Boolean =
        grouped && position in visible.indices && visible[position] !is SettingItem.Header

    fun startsCard(position: Int): Boolean = isGroupCard(position) && !isGroupCard(position - 1)

    fun endsCard(position: Int): Boolean = isGroupCard(position) && !isGroupCard(position + 1)

    private val openHere: MutableSet<Int>
        get() = expandedAccordions.getOrPut(page) { mutableSetOf() }

    // The open accordions as page and title pairs, one after another, for
    // the activity to carry across a recreate. Set before the first submit,
    // the list's saved scroll finds the rows it was on.
    var openAccordions: IntArray
        get() = expandedAccordions.flatMap { (key, titles) -> titles.flatMap { listOf(key, it) } }.toIntArray()
        set(pairs) {
            expandedAccordions.clear()
            for (i in 0 until pairs.size / 2) {
                expandedAccordions.getOrPut(pairs[2 * i]) { mutableSetOf() }.add(pairs[2 * i + 1])
            }
            rebuild()
        }

    private fun toggleAccordion(@StringRes titleRes: Int) {
        if (!openHere.remove(titleRes)) openHere.add(titleRes)
        rebuild()
    }

    fun clearHighlight() {
        highlightTitle = 0
    }

    // Position of the row with this title in the currently visible list, or
    // -1 (also for title 0, which Preview headers share).
    fun positionOf(@StringRes titleRes: Int): Int =
        if (titleRes == 0) -1 else visible.indexOfFirst { it.title == titleRes }

    // page names the list: a section's title, or 0 for one without
    // accordions. It picks which accordions are open.
    fun submit(items: List<SettingItem>, prefs: LauncherPrefs, @StringRes page: Int = 0, grouped: Boolean = false) {
        this.items = items
        this.prefs = prefs
        this.page = page
        this.grouped = grouped
        previews.latestPrefs = prefs
        rebuild()
    }

    fun updatePrefs(prefs: LauncherPrefs) {
        this.prefs = prefs
        previews.latestPrefs = prefs
        rebuild()
    }

    private fun rebuild() {
        // A pending search highlight must be reachable: expand the accordion
        // that covers it before computing visibility.
        if (highlightTitle != 0) {
            var scope = 0
            for (item in items) {
                when (item) {
                    is SettingItem.Header, is SettingItem.Preview -> scope = 0
                    is SettingItem.Accordion -> scope = item.title
                    else -> if (item.title == highlightTitle) {
                        if (scope != 0) openHere.add(scope)
                        // Search lands on the Label card for the controls
                        // inside it, so the card opens too.
                        if (item is SettingItem.LabelGroup) openHere.add(item.title)
                    }
                }
            }
        }
        val open = openHere
        val out = ArrayList<SettingItem>(items.size)
        var hidden = false
        for (item in items) {
            when (item) {
                is SettingItem.Header, is SettingItem.Preview, is SettingItem.Accordion -> hidden = false
                else -> {}
            }
            if (!hidden && item.visibleIf(prefs)) out.add(item)
            if (item is SettingItem.Accordion) hidden = item.title !in open
        }
        visible = out
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = visible.size

    override fun getItemViewType(position: Int): Int = when (visible[position]) {
        is SettingItem.Toggle -> TYPE_TOGGLE
        is SettingItem.Slider -> TYPE_SLIDER
        is SettingItem.Row -> if (grouped) TYPE_GROUP_ROW else TYPE_ROW
        is SettingItem.SectionLink -> TYPE_SECTION
        is SettingItem.Preview -> TYPE_PREVIEW
        is SettingItem.Header -> if (grouped) TYPE_GROUP_LABEL else TYPE_HEADER
        is SettingItem.Accordion -> TYPE_ACCORDION
        is SettingItem.StyleTarget -> TYPE_STYLE_TARGET
        is SettingItem.Card -> TYPE_CARD
        is SettingItem.LabelGroup -> TYPE_LABEL_GROUP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TOGGLE -> ToggleHolder(ItemSettingToggleBinding.inflate(inflater, parent, false))
            TYPE_SLIDER -> SliderHolder(ItemSettingSliderBinding.inflate(inflater, parent, false), previews)
            TYPE_PREVIEW -> PreviewHolder(ItemSettingPreviewBinding.inflate(inflater, parent, false), previews)
            TYPE_HEADER -> HeaderHolder(ItemSettingHeaderBinding.inflate(inflater, parent, false))
            TYPE_ACCORDION -> AccordionHolder(ItemSettingAccordionBinding.inflate(inflater, parent, false), ::toggleAccordion)
            TYPE_STYLE_TARGET -> StyleTargetHolder(
                ItemSettingStyleTargetBinding.inflate(inflater, parent, false)
            ) { onRowTapped?.invoke(it) }
            TYPE_CARD -> CardHolder(ItemSettingCardBinding.inflate(inflater, parent, false))
            TYPE_LABEL_GROUP -> LabelGroupHolder(
                ItemSettingLabelGroupBinding.inflate(inflater, parent, false), previews, ::toggleAccordion
            ) { onRowTapped?.invoke(it) }
            TYPE_SECTION -> SectionHolder(
                ItemSettingSectionBinding.inflate(inflater, parent, false)
            ) { onRowTapped?.invoke(it) }
            TYPE_GROUP_ROW -> GroupRowHolder(
                ItemSettingGroupRowBinding.inflate(inflater, parent, false)
            ) { onRowTapped?.invoke(it) }
            TYPE_GROUP_LABEL -> GroupLabelHolder(ItemSettingGroupLabelBinding.inflate(inflater, parent, false))
            else -> RowHolder(
                ItemSettingRowBinding.inflate(inflater, parent, false)
            ) { onRowTapped?.invoke(it) }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = visible[position]) {
            is SettingItem.Toggle -> (holder as ToggleHolder).bind(item, prefs, grouped)
            is SettingItem.Slider -> (holder as SliderHolder).bind(item, prefs)
            is SettingItem.Row ->
                if (holder is GroupRowHolder) holder.bind(item, prefs) else (holder as RowHolder).bind(item, prefs)
            is SettingItem.SectionLink -> (holder as SectionHolder).bind(item, prefs)
            is SettingItem.Preview -> (holder as PreviewHolder).bind(item)
            is SettingItem.Header ->
                if (holder is GroupLabelHolder) holder.bind(item) else (holder as HeaderHolder).bind(item)
            is SettingItem.Accordion ->
                (holder as AccordionHolder).bind(item, item.title in openHere)
            is SettingItem.StyleTarget -> (holder as StyleTargetHolder).bind(item, prefs)
            is SettingItem.Card -> (holder as CardHolder).bind(item)
            is SettingItem.LabelGroup ->
                (holder as LabelGroupHolder).bind(item, prefs, item.title in openHere)
        }
        clipToCard(holder.itemView, position)
        flashIfLanding(holder, visible[position])
    }

    private fun flashIfLanding(holder: RecyclerView.ViewHolder, item: SettingItem) {
        if (highlightTitle == 0 || item.title != highlightTitle) return
        highlightTitle = 0
        // The Label card's own plate is its root's background, which the
        // flash would clear; its header row has none.
        flashHighlight((holder as? LabelGroupHolder)?.header ?: holder.itemView)
    }

    // A card's first and last rows are clipped to its rounded corners, so a
    // press ripple or the search-landing wash doesn't square them off. The
    // row's corners are the card's less the card's 4dp inset.
    private fun clipToCard(view: View, position: Int) {
        val top = startsCard(position)
        val bottom = endsCard(position)
        if (!top && !bottom) {
            view.clipToOutline = false
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
            return
        }
        val radius = view.resources.displayMetrics.density * ROW_CORNER_DP
        val overhang = radius.toInt() + 1
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(
                    0, if (top) 0 else -overhang, v.width, if (bottom) v.height else v.height + overhang, radius
                )
            }
        }
        view.clipToOutline = true
    }

    // Search-landing cue: a translucent accent wash that fades out. Rows keep
    // no background of their own (the ripple is a foreground), so clearing to
    // null on end restores the resting state exactly.
    private fun flashHighlight(view: View) {
        val accent = view.context.getColor(com.supernova.launcher.R.color.accent) and 0xFFFFFF
        android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = 250
            duration = 750
            addUpdateListener { animator ->
                val alpha = ((animator.animatedValue as Float) * 0x44).toInt()
                view.setBackgroundColor((alpha shl 24) or accent)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.background = null
                }
            })
            start()
        }
    }

    private class ToggleHolder(private val binding: ItemSettingToggleBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem.Toggle, prefs: LauncherPrefs, grouped: Boolean) {
            // In a card it is an option of the tiled row above (Theme's Use
            // location): its text starts where that row's text does.
            val density = binding.root.resources.displayMetrics.density
            binding.root.setPaddingRelative(
                ((if (grouped) GROUP_TEXT_START_DP else ROW_SIDE_DP) * density).toInt(), binding.root.paddingTop,
                ((if (grouped) GROUP_SIDE_DP else ROW_SIDE_DP) * density).toInt(), binding.root.paddingBottom
            )
            binding.settingTitle.setText(item.title)
            if (item.subtitle != 0) {
                binding.settingSubtitle.setText(item.subtitle)
                binding.settingSubtitle.visibility = View.VISIBLE
            } else {
                binding.settingSubtitle.visibility = View.GONE
            }
            // Detach the listener before syncing state so rebinding never
            // reports a phantom user toggle.
            binding.settingSwitch.setOnCheckedChangeListener(null)
            binding.settingSwitch.isChecked = item.get(prefs)
            binding.settingSwitch.setOnCheckedChangeListener { _, checked -> item.set(checked) }
            binding.root.setOnClickListener { binding.settingSwitch.toggle() }
        }
    }

    private class SliderHolder(
        private val binding: ItemSettingSliderBinding,
        private val previews: PreviewController
    ) : RecyclerView.ViewHolder(binding.root) {
        // The bound item's max: Big grid can raise it, and a value set while
        // it was on keeps its room after it goes off, so a step from there
        // moves from the real value rather than from a clamped bar.
        private var max = 0

        fun bind(item: SettingItem.Slider, prefs: LauncherPrefs) {
            binding.settingTitle.setText(item.title)
            val current = item.get(prefs)
            max = maxOf(item.maxFor(prefs), current)
            binding.settingSeek.max = max - item.min
            if (!binding.settingSeek.isPressed) {
                binding.settingSeek.progress = current - item.min
            }
            binding.settingValue.text = item.display(current)
            binding.settingSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = item.min + progress
                    binding.settingValue.text = item.display(value)
                    if (fromUser) previews.onTick(item, value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    previews.isTracking = true
                }

                // Committing on release keeps a drag from writing a value (and
                // rebuilding the workspace) on every tick.
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    previews.isTracking = false
                    item.set(item.min + seekBar.progress)
                }
            })
            binding.stepDown.setOnClickListener { step(item, -item.step) }
            binding.stepUp.setOnClickListener { step(item, item.step) }
        }

        // Steppers commit immediately. The bar and label move first so rapid
        // taps compose before the DataStore write round-trips.
        private fun step(item: SettingItem.Slider, delta: Int) {
            val raw = item.min + binding.settingSeek.progress + delta
            // Snap onto the step grid so a value that drifted off it (e.g.
            // old float-truncation writes) recovers instead of staying offset.
            val snapped = item.min + (raw - item.min + item.step / 2) / item.step * item.step
            val target = snapped.coerceIn(item.min, max)
            binding.settingSeek.progress = target - item.min
            binding.settingValue.text = item.display(target)
            item.set(target)
        }
    }

    private class RowHolder(
        private val binding: ItemSettingRowBinding,
        private val onTap: (View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem.Row, prefs: LauncherPrefs) {
            if (item.titleText != null) {
                binding.settingTitle.text = item.titleText
            } else {
                binding.settingTitle.setText(item.title)
            }
            val subtitle = item.subtitle(prefs)
            // Short values read as an outlined pill on the right; longer
            // descriptions stay under the title.
            val asPill = subtitle != null && subtitle.length <= 24 && '\n' !in subtitle
            binding.settingValuePill.text = if (asPill) subtitle else null
            binding.settingValuePill.visibility = if (asPill) View.VISIBLE else View.GONE
            binding.settingSubtitle.text = if (asPill) null else subtitle
            binding.settingSubtitle.visibility =
                if (subtitle == null || asPill) View.GONE else View.VISIBLE
            binding.root.setOnClickListener {
                onTap(it)
                item.onClick()
            }
        }
    }

    // A Row in a card: its icon tile, the title, and its value on the right
    // (with the swatch dot before it) - or, too long for there, under the
    // title. With no icon, the text lines up with the tiled rows' text.
    private class GroupRowHolder(
        private val binding: ItemSettingGroupRowBinding,
        private val onTap: (View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem.Row, prefs: LauncherPrefs) {
            if (item.titleText != null) {
                binding.settingTitle.text = item.titleText
            } else {
                binding.settingTitle.setText(item.title)
            }
            val density = binding.root.resources.displayMetrics.density
            val icon = item.look.icon
            binding.groupRowTile.visibility = if (icon != 0) View.VISIBLE else View.GONE
            if (icon != 0) binding.groupRowIcon.setImageResource(icon)
            val twoLines = bindValue(item, prefs)
            // Two lines get the taller row's padding: 64dp, as on the root list.
            val vertical = ((if (twoLines) TWO_LINE_PADDING_DP else ONE_LINE_PADDING_DP) * density).toInt()
            binding.root.setPaddingRelative(
                ((if (icon != 0) GROUP_SIDE_DP else GROUP_TEXT_START_DP) * density).toInt(), vertical,
                (GROUP_SIDE_DP * density).toInt(), vertical
            )
            binding.root.setOnClickListener {
                onTap(it)
                item.onClick()
            }
        }

        // The value on the right, with its swatch, or under the title when
        // it is too long for there. True when it went under the title.
        private fun bindValue(item: SettingItem.Row, prefs: LauncherPrefs): Boolean {
            val value = item.subtitle(prefs)
            val trailing = value != null && value.length <= TRAILING_VALUE_MAX && '\n' !in value
            binding.groupRowValue.text = if (trailing) value else null
            binding.groupRowValue.visibility = if (trailing) View.VISIBLE else View.GONE
            val below = value != null && !trailing
            binding.settingSubtitle.text = if (below) value else null
            binding.settingSubtitle.visibility = if (below) View.VISIBLE else View.GONE
            val swatch = item.look.swatch?.invoke(prefs)
            binding.groupRowSwatch.visibility = if (swatch != null && trailing) View.VISIBLE else View.GONE
            if (swatch != null) binding.groupRowSwatch.backgroundTintList = ColorStateList.valueOf(swatch)
            return below
        }
    }

    private class GroupLabelHolder(private val binding: ItemSettingGroupLabelBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem.Header) {
            binding.settingHeader.setText(item.title)
        }
    }

    private class SectionHolder(
        private val binding: ItemSettingSectionBinding,
        private val onTap: (View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem.SectionLink, prefs: LauncherPrefs) {
            binding.sectionTitle.setText(item.title)
            if (item.icon != 0) {
                binding.sectionIcon.setImageResource(item.icon)
            } else {
                binding.sectionIcon.setImageDrawable(null)
            }
            val subtitle = item.subtitle(prefs)
            binding.sectionSubtitle.text = subtitle
            binding.sectionSubtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            binding.root.setOnClickListener {
                onTap(it)
                item.onClick()
            }
        }
    }

    private class PreviewHolder(
        private val binding: ItemSettingPreviewBinding,
        private val previews: PreviewController
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem.Preview) {
            // Mirrors the isPressed seekbar guard: a prefs emission mid-drag
            // must not rebuild the preview out from under the tick stream.
            if (previews.isTracking) return
            previews.attach(binding.root, item.kind)
        }
    }

    private class StyleTargetHolder(
        private val binding: ItemSettingStyleTargetBinding,
        private val onTap: (View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem.StyleTarget, prefs: LauncherPrefs) {
            binding.settingTitle.setText(item.title)
            val resolved = com.supernova.launcher.ui.theme.Styles
                .resolve(binding.root.context, prefs).of(item.surface)
            val density = binding.root.resources.displayMetrics.density
            binding.styleStrip.background = com.supernova.launcher.ui.theme.SurfaceShapes.background(
                resolved.surface,
                resolved.cornerRadiiDp?.let { dpArr -> FloatArray(4) { dpArr[it] * density } },
                resolved.cornerCuts,
                12f * density
            )
            binding.styleStripText.setTextColor(resolved.text)
            binding.styleStripAccent.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(resolved.accent)
            }
            binding.root.setOnClickListener {
                onTap(it)
                item.onClick()
            }
        }
    }

    private class CardHolder(private val binding: ItemSettingCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem.Card) {
            binding.cardTitle.text = item.titleText
            val gear = item.onGear
            binding.cardGear.visibility = if (gear == null) View.GONE else View.VISIBLE
            binding.cardGear.setOnClickListener { gear?.invoke() }
            // Rebuilt per bind: binds are rare (a card edit, a style change)
            // and the rendering is the card's own view tree.
            binding.cardPreviewContainer.removeAllViews()
            binding.cardPreviewContainer.addView(item.build(binding.cardPreviewContainer))
        }
    }

    private class LabelGroupHolder(
        private val binding: ItemSettingLabelGroupBinding,
        private val previews: PreviewController,
        private val onToggle: (Int) -> Unit,
        private val onTap: (View) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        val header: View get() = binding.labelHeader

        init {
            // The open card and the font box are rounded; clipping to their
            // outlines keeps the press ripples inside the corners.
            binding.root.clipToOutline = true
            binding.labelFontBox.clipToOutline = true
        }

        fun bind(item: SettingItem.LabelGroup, prefs: LauncherPrefs, expanded: Boolean) {
            binding.labelTitle.setText(item.title)
            binding.labelChevron.rotation = if (expanded) 180f else 0f
            binding.labelBody.visibility = if (expanded) View.VISIBLE else View.GONE
            // Closed, the group is a plain row; open, the header and the
            // controls share one plate.
            if (expanded) {
                binding.root.setBackgroundResource(com.supernova.launcher.R.drawable.bg_setting_group)
            } else {
                binding.root.background = null
            }
            binding.labelHeader.setOnClickListener { onToggle(item.title) }
            bindCheck(binding.labelSwitch, item.shown(prefs), item.setShown)

            // Same contract as a Slider row: ticks preview, release commits,
            // and a rebind never yanks the thumb out from under a finger.
            val seek = binding.labelSizeSeek
            seek.max = item.sizeMax - item.sizeMin
            val size = item.size(prefs)
            if (!seek.isPressed) seek.progress = size - item.sizeMin
            binding.labelSizeValue.text = item.sizeDisplay(size)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = item.sizeMin + progress
                    binding.labelSizeValue.text = item.sizeDisplay(value)
                    if (fromUser) previews.onTick(item.previewSize, value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    previews.isTracking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    previews.isTracking = false
                    item.setSize(item.sizeMin + seekBar.progress)
                }
            })

            binding.labelFontValue.text = item.font(prefs)
            // The font menu drops out of the box itself.
            binding.labelFontBox.setOnClickListener {
                onTap(it)
                item.pickFont()
            }

            bindCheck(binding.labelShadowCheck, item.shadow(prefs), item.setShadow)
            binding.labelShadowCell.setOnClickListener { binding.labelShadowCheck.toggle() }
            bindCheck(binding.labelSingleLineCheck, item.singleLine(prefs), item.setSingleLine)
            binding.labelSingleLineCell.setOnClickListener { binding.labelSingleLineCheck.toggle() }
        }

        // Detached while syncing, so a rebind never reports a phantom toggle.
        private fun bindCheck(button: CompoundButton, checked: Boolean, set: (Boolean) -> Unit) {
            button.setOnCheckedChangeListener(null)
            button.isChecked = checked
            button.setOnCheckedChangeListener { _, isChecked -> set(isChecked) }
        }
    }

    private class HeaderHolder(private val binding: ItemSettingHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem.Header) {
            binding.settingHeader.setText(item.title)
        }
    }

    private class AccordionHolder(
        private val binding: ItemSettingAccordionBinding,
        private val onToggle: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SettingItem.Accordion, expanded: Boolean) {
            binding.settingTitle.setText(item.title)
            binding.accordionChevron.rotation = if (expanded) 180f else 0f
            binding.root.setOnClickListener { onToggle(item.title) }
        }
    }

    private companion object {
        const val TYPE_TOGGLE = 0
        const val TYPE_SLIDER = 1
        const val TYPE_ROW = 2
        const val TYPE_PREVIEW = 3
        const val TYPE_HEADER = 4
        const val TYPE_ACCORDION = 5
        const val TYPE_STYLE_TARGET = 6
        const val TYPE_CARD = 7
        const val TYPE_SECTION = 8
        const val TYPE_LABEL_GROUP = 9
        const val TYPE_GROUP_ROW = 10
        const val TYPE_GROUP_LABEL = 11

        // A section page's rows run 20dp from the sides; a card's, 16dp in
        // from its edges, their text 72dp in, past the 40dp tile.
        const val ROW_SIDE_DP = 20
        const val GROUP_SIDE_DP = 16
        const val GROUP_TEXT_START_DP = 72
        const val ROW_CORNER_DP = 16f
        // Values longer than this go under the title, not on the right.
        const val TRAILING_VALUE_MAX = 24
        // A card row's vertical padding: 56dp on one line, 64dp on two.
        const val ONE_LINE_PADDING_DP = 8
        const val TWO_LINE_PADDING_DP = 12
    }
}
