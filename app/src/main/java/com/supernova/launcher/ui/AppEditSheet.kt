package com.supernova.launcher.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R
import com.supernova.launcher.data.AppOverride
import com.supernova.launcher.data.GalleryIconCodec
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.databinding.SheetAppEditBinding
import com.supernova.launcher.databinding.SheetIconPickerBinding
import com.supernova.launcher.ui.settings.EnumLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The per-app Edit sheet: rename, hide label, per-app shape, custom icon from
// any installed pack or a gallery photo. Saves one AppOverride keyed by app
// identity.
object AppEditSheet {

    // A placed item that isn't an app icon - a pinned shortcut, a launcher
    // action, a picked activity - edits its own label. The app-level rows
    // (hide label, shape, icon, photo, reset) are the app's and go; a
    // shortcut used to open its app's sheet, so renaming it renamed the app
    // everywhere, and an item with no app opened nothing at all.
    class ItemLabel(val label: String, val onSave: (String) -> Unit)

    fun show(
        context: Context,
        scope: CoroutineScope,
        viewModel: LauncherViewModel,
        // Null only with itemLabel, for an item with no app behind it.
        app: LaunchableApp?,
        // Host-provided photo picker (the ActivityResult launcher must be
        // registered on the activity); null hides the photo option.
        pickPhoto: (((android.net.Uri?) -> Unit) -> Unit)? = null,
        // Per-item swipe editing. Non-null only when editing a placed
        // workspace item: `swipeLabels` reads the current up/down labels and
        // `onEditSwipe` opens the host picker, calling back with the chosen
        // label (null = cleared) so the row can refresh without racing the
        // async model write.
        swipeLabels: ((down: Boolean) -> String?)? = null,
        onEditSwipe: ((down: Boolean, onPicked: (String?) -> Unit) -> Unit)? = null,
        itemLabel: ItemLabel? = null
    ) {
        if (app == null && itemLabel == null) return
        val binding = SheetAppEditBinding.inflate(LayoutInflater.from(context))
        val dialog = com.supernova.launcher.ui.Sheets.open(context)
        dialog.setContentView(binding.root)

        val rawLabel = itemLabel?.label ?: app?.let(viewModel::rawLabelOf).orEmpty()
        val current = if (itemLabel == null && app != null) viewModel.overrideFor(app) else AppOverride()
        var chosenShape: String? = current.iconShape
        var chosenPack: String? = current.iconPackPackage
        var chosenIcon: String? = current.iconDrawableName
        var chosenPhoto: String? = current.iconBase64
        var chosenVariant: String? = current.iconVariant

        binding.editTitle.text = rawLabel
        if (viewModel.state.value.prefs.labsShowComponent && app != null) {
            binding.editComponent.text = "${app.packageName}/${app.className}"
            binding.editComponent.visibility = android.view.View.VISIBLE
        }
        binding.editLabel.setText(current.customLabel ?: rawLabel)
        binding.hideLabelSwitch.isChecked = current.hideLabel

        fun refreshRows() {
            val shapeLabel = chosenShape?.let { context.getString(EnumLabels.of(IconShape.fromName(it))) }
                ?: context.getString(R.string.option_default)
            binding.shapeValue.text = shapeLabel
            val iconLabel = when {
                chosenPhoto != null -> context.getString(R.string.choose_photo)
                chosenIcon != null -> chosenIcon
                else -> context.getString(R.string.option_default)
            }
            binding.iconValue.text = iconLabel
            val variantLabel = when (chosenVariant) {
                "THEMED" -> context.getString(R.string.variant_themed)
                "ORIGINAL" -> context.getString(R.string.variant_original)
                else -> context.getString(R.string.variant_default)
            }
            binding.variantValue.text = variantLabel
        }
        refreshRows()

        // These two rows ask the same kind of question the settings screen
        // does - one value, a short list of alternatives - so they answer it
        // the same way: a menu at the row rather than a dialog over the sheet.
        // Anchored to a view inside the sheet's own window, so the menu lands
        // above the sheet rather than behind it.
        val surface = com.supernova.launcher.ui.theme.Styles
            .resolve(context, viewModel.state.value.prefs)
            .of(com.supernova.launcher.ui.theme.SurfaceId.POPUP)
        val animate = Animations.enabled(viewModel.state.value.prefs)
        // Where each row was pressed, watched rather than consumed so the
        // row's own click listener still runs.
        var rowTouchX = 0f
        val trackTouch = android.view.View.OnTouchListener { view, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                val location = IntArray(2)
                view.getLocationInWindow(location)
                rowTouchX = event.x + location[0]
            }
            false
        }
        binding.shapeRow.setOnTouchListener(trackTouch)
        binding.variantRow.setOnTouchListener(trackTouch)

        binding.shapeRow.setOnClickListener { row ->
            val options = listOf<IconShape?>(null) + IconShape.entries
            val labels = options.map { shape ->
                if (shape == null) context.getString(R.string.option_default)
                else context.getString(EnumLabels.of(shape))
            }
            val currentIndex = options.indexOfFirst { it?.name == chosenShape }.coerceAtLeast(0)
            ChoiceMenu.show(row, rowTouchX, surface, labels, currentIndex, animate) { which ->
                chosenShape = options[which]?.name
                refreshRows()
            }
        }

        binding.variantRow.setOnClickListener { row ->
            val values = listOf<String?>(null, "THEMED", "ORIGINAL")
            val labels = listOf(
                context.getString(R.string.variant_default),
                context.getString(R.string.variant_themed),
                context.getString(R.string.variant_original)
            )
            ChoiceMenu.show(
                row, rowTouchX, surface, labels,
                values.indexOf(chosenVariant).coerceAtLeast(0), animate
            ) { which ->
                chosenVariant = values[which]
                refreshRows()
            }
        }

        binding.iconRow.setOnClickListener {
            IconPickerSheet.show(context, scope, viewModel, allowDefault = true) { pack, name ->
                chosenPack = pack
                chosenIcon = name
                // A pack icon (or a reset to default) replaces any photo.
                chosenPhoto = null
                refreshRows()
            }
        }

        if (swipeLabels != null && onEditSwipe != null) {
            // Track the labels locally: the picker commits asynchronously, so
            // re-reading model state right after a pick would still be stale;
            // the picker hands back the chosen label directly instead.
            var upLabel = swipeLabels(false)
            var downLabel = swipeLabels(true)
            fun render() {
                val none = context.getString(R.string.swipe_none)
                binding.swipeUpValue.text = upLabel ?: none
                binding.swipeDownValue.text = downLabel ?: none
            }
            binding.swipeUpRow.visibility = android.view.View.VISIBLE
            binding.swipeDownRow.visibility = android.view.View.VISIBLE
            render()
            binding.swipeUpRow.setOnClickListener {
                onEditSwipe(false) { picked -> upLabel = picked; render() }
            }
            binding.swipeDownRow.setOnClickListener {
                onEditSwipe(true) { picked -> downLabel = picked; render() }
            }
        }

        binding.photoRow.visibility = if (pickPhoto == null) android.view.View.GONE else android.view.View.VISIBLE
        binding.photoRow.setOnClickListener {
            pickPhoto?.invoke { uri ->
                if (uri == null) return@invoke
                // Crop + encode off the main thread; the sheet stays open and
                // commits the result with Save like every other field.
                scope.launch {
                    val encoded = withContext(Dispatchers.IO) {
                        GalleryIconCodec.encode(context.applicationContext, uri)
                    }
                    if (encoded == null) {
                        Toast.makeText(context, R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    chosenPhoto = encoded
                    chosenPack = null
                    chosenIcon = null
                    refreshRows()
                }
            }
        }

        if (itemLabel != null) {
            listOf(
                binding.hideLabelRow, binding.shapeRow, binding.variantRow,
                binding.iconRow, binding.photoRow, binding.resetButton
            ).forEach { it.visibility = android.view.View.GONE }
            binding.saveButton.setOnClickListener {
                val typed = binding.editLabel.text?.toString().orEmpty().trim()
                if (typed.isNotEmpty() && typed != itemLabel.label) itemLabel.onSave(typed)
                dialog.dismiss()
            }
            dialog.show()
            return
        }
        // From here on an app is being edited.
        app ?: return

        binding.resetButton.setOnClickListener {
            viewModel.setAppOverride(app) { AppOverride() }
            dialog.dismiss()
        }

        binding.saveButton.setOnClickListener {
            val typed = binding.editLabel.text?.toString().orEmpty().trim()
            val customLabel = typed.takeIf { it.isNotEmpty() && it != rawLabel }
            viewModel.setAppOverride(app) {
                AppOverride(
                    customLabel = customLabel,
                    hideLabel = binding.hideLabelSwitch.isChecked,
                    iconShape = chosenShape,
                    iconPackPackage = if (chosenIcon != null) chosenPack else null,
                    iconDrawableName = chosenIcon,
                    iconBase64 = chosenPhoto,
                    iconVariant = chosenVariant
                )
            }
            dialog.dismiss()
        }

        dialog.show()
    }
}

// Pack -> drawable-grid picker; used for per-app icons and folder icons.
object IconPickerSheet {

    fun show(
        context: Context,
        scope: CoroutineScope,
        viewModel: LauncherViewModel,
        allowDefault: Boolean = false,
        // When set, a "Photo" entry lets the caller pick a gallery image.
        onPickPhoto: (() -> Unit)? = null,
        onPick: (pack: String?, name: String?) -> Unit
    ) {
        scope.launch {
            val packs = viewModel.availableIconPacks()
            // Each entry pairs its label with the action tapping it runs.
            val entries = buildList<Pair<String, () -> Unit>> {
                if (allowDefault) add(context.getString(R.string.option_default) to { onPick(null, null) })
                if (onPickPhoto != null) add(context.getString(R.string.choose_photo) to onPickPhoto)
                packs.forEach { pack ->
                    add(pack.label to { showGrid(context, scope, viewModel, pack.packageName, onPick) })
                }
            }
            if (entries.isEmpty()) {
                Toast.makeText(context, R.string.icon_pack_none_installed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.change_icon)
                .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showGrid(
        context: Context,
        scope: CoroutineScope,
        viewModel: LauncherViewModel,
        packPackage: String,
        onPick: (String?, String?) -> Unit
    ) {
        scope.launch {
            val names = viewModel.packDrawableNames(packPackage)
            if (names.isEmpty()) {
                Toast.makeText(context, R.string.no_icons_in_pack, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val binding = SheetIconPickerBinding.inflate(LayoutInflater.from(context))
            val dialog = com.supernova.launcher.ui.Sheets.open(context)
            dialog.setContentView(binding.root)
            binding.iconGrid.layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 5)
            binding.iconGrid.adapter = IconChoiceAdapter(viewModel, packPackage, names) { name ->
                onPick(packPackage, name)
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    private class IconChoiceAdapter(
        private val viewModel: LauncherViewModel,
        private val packPackage: String,
        private val names: List<String>,
        private val onPick: (String) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<IconChoiceAdapter.Holder>() {

        class Holder(val view: android.widget.ImageView) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_icon_choice, parent, false) as android.widget.ImageView
            return Holder(view)
        }

        override fun getItemCount(): Int = names.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val name = names[position]
            // Lazy per-bind decode: the RecyclerView only materializes what
            // scrolls into view, so a 3,000-icon pack stays tractable.
            holder.view.setImageDrawable(viewModel.appCatalog().packDrawable(packPackage, name))
            holder.view.setOnClickListener { onPick(name) }
        }
    }
}
