package com.supernova.launcher.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R
import com.supernova.launcher.data.AppIconSource
import com.supernova.launcher.data.AppOverride
import com.supernova.launcher.data.GalleryIconCodec
import com.supernova.launcher.data.IconSearch
import com.supernova.launcher.data.LaunchableApp
import com.supernova.launcher.databinding.SheetAppEditBinding
import com.supernova.launcher.databinding.SheetIconPickerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The per-app Edit sheet: rename, hide label, per-app shape, custom icon from
// any installed pack or a gallery photo. Saves one AppOverride keyed by app
// identity.
object AppEditSheet {

    // A placed shortcut - a pinned app shortcut, a picked activity - edits
    // its own name, hidden label, icon, shape and style, saved on the item
    // rather than the app: a shortcut used to open its app's sheet, so
    // renaming it renamed the app everywhere. `originalLabel` is the name
    // Reset puts back, when it is known.
    class ShortcutEdit(
        val label: String,
        val edit: AppOverride,
        val originalLabel: String?,
        // The shortcut's icon drawn with an edit, for the preview.
        val preview: ((AppOverride) -> android.graphics.drawable.Drawable?)? = null,
        val onSave: (label: String, edit: AppOverride) -> Unit
    )

    fun show(
        context: Context,
        scope: CoroutineScope,
        viewModel: LauncherViewModel,
        // Null only with shortcut, for one with no app behind it.
        app: LaunchableApp?,
        // Host-provided photo picker (the ActivityResult launcher must be
        // registered on the activity); null hides the photo options.
        pickPhoto: PhotoPicker? = null,
        // Per-item swipe editing. Non-null only when editing a placed
        // workspace item: `swipeLabels` reads the current up/down labels and
        // `onEditSwipe` opens the host picker, calling back with the chosen
        // label (null = cleared) so the row can refresh without racing the
        // async model write.
        swipeLabels: ((down: Boolean) -> String?)? = null,
        onEditSwipe: ((down: Boolean, onPicked: (String?) -> Unit) -> Unit)? = null,
        shortcut: ShortcutEdit? = null
    ) {
        if (app == null && shortcut == null) return
        val binding = SheetAppEditBinding.inflate(LayoutInflater.from(context))
        val dialog = com.supernova.launcher.ui.Sheets.open(context)
        dialog.setContentView(binding.root)

        val rawLabel = shortcut?.label ?: app?.let(viewModel::rawLabelOf).orEmpty()
        val current = shortcut?.edit ?: app?.let(viewModel::overrideFor) ?: AppOverride()

        dressForPreview(context, dialog, binding)
        if (viewModel.state.value.prefs.labsShowComponent && app != null && shortcut == null) {
            binding.editComponent.text = "${app.packageName}/${app.className}"
            binding.editComponent.visibility = android.view.View.VISIBLE
        }
        binding.hideLabelSwitch.isChecked = current.hideLabel
        // The whole row toggles it, as the sheet's other switch rows do.
        binding.hideLabelRow.setOnClickListener { binding.hideLabelSwitch.toggle() }

        // The name with no edit: shown in the empty field, and saved when the
        // field is left empty.
        val defaultLabel = shortcut?.let { it.originalLabel ?: it.label } ?: rawLabel
        binding.editLabelLayout.placeholderText = defaultLabel
        // Only a name of the user's own is in the field; the real one is
        // the placeholder.
        binding.editLabel.setText((shortcut?.label ?: current.customLabel)?.takeIf { it != defaultLabel }.orEmpty())
        val draft = AppEditDraft(binding, scope, viewModel, defaultLabel, saved = current)
        draft.preview = shortcut?.preview ?: app?.let { a -> { edit -> viewModel.appCatalog().previewIcon(a, edit) } }
        draft.bind()
        bindIconRow(scope, viewModel, draft, pickPhoto)

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

        if (shortcut != null) {
            binding.resetButton.setOnClickListener {
                shortcut.onSave(shortcut.originalLabel ?: shortcut.label, AppOverride())
                dialog.dismiss()
            }
            binding.saveButton.setOnClickListener {
                // A shortcut's name is saved on the item, not in the edit.
                shortcut.onSave(draft.typedLabel().ifEmpty { defaultLabel }, draft.edit().copy(customLabel = null))
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
            viewModel.setAppOverride(app) { draft.edit() }
            dialog.dismiss()
        }

        dialog.show()
    }

    // The preview names the item, so the sheet has no title of its own. Its
    // card is a hole through the sheet (WallpaperWell); what shows through
    // it is the launcher's window, so the launcher's own content fades out
    // while the sheet is up, leaving that window showing only the wallpaper,
    // and the sheet doesn't dim it. The sheet covers nearly the whole
    // screen, so nothing is lost; the content comes back as it closes.
    private fun dressForPreview(context: Context, dialog: android.app.Dialog, binding: SheetAppEditBinding) {
        binding.editTitle.visibility = android.view.View.GONE
        dialog.window?.let { window ->
            window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            window.setDimAmount(0f)
        }
        val home = context.findActivity()?.findViewById<android.view.View>(android.R.id.content) ?: return
        home.animate().alpha(0f).setDuration(HOME_FADE_MS)
        dialog.setOnDismissListener { home.animate().alpha(1f).setDuration(HOME_FADE_MS) }
    }

    private fun Context.findActivity(): android.app.Activity? {
        var current: Context? = this
        while (current is android.content.ContextWrapper) {
            if (current is android.app.Activity) return current
            current = current.baseContext
        }
        return null
    }

    private const val HOME_FADE_MS = 150L

    // One row for the icon, wherever it comes from: Default, another app's
    // icon, a photo or an icon pack. A pick lands in the draft, and the
    // preview shows it at once.
    private fun bindIconRow(
        scope: CoroutineScope,
        viewModel: LauncherViewModel,
        draft: AppEditDraft,
        pickPhoto: PhotoPicker?
    ) {
        val context = draft.binding.root.context
        draft.binding.iconRow.setOnClickListener {
            val onPhoto = pickPhoto?.let { picker ->
                { fromFiles: Boolean ->
                    picker(fromFiles) { uri ->
                        if (uri == null) return@picker
                        // Crop + encode off the main thread; the sheet stays
                        // open and commits the result with Save like every
                        // other field.
                        scope.launch {
                            val encoded = withContext(Dispatchers.IO) {
                                GalleryIconCodec.encode(context.applicationContext, uri)
                            }
                            if (encoded == null) {
                                Toast.makeText(context, R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            draft.photo = encoded
                            draft.pack = null
                            draft.icon = null
                            draft.refresh()
                        }
                    }
                }
            }
            IconPickerSheet.show(context, scope, viewModel, allowDefault = true, onPickPhoto = onPhoto) { pack, name ->
                draft.pack = pack
                draft.icon = name
                // A pack icon (or a reset to default) replaces any photo.
                draft.photo = null
                draft.refresh()
            }
        }
    }
}

// A photo source (gallery or files), then the picked image's uri, or null.
typealias PhotoPicker = (fromFiles: Boolean, onPicked: (android.net.Uri?) -> Unit) -> Unit

// Source -> icon-grid picker; used for per-app icons and folder icons. The
// sources: Default, every installed app's own icon, a photo, each icon pack.
object IconPickerSheet {

    fun show(
        context: Context,
        scope: CoroutineScope,
        viewModel: LauncherViewModel,
        allowDefault: Boolean = false,
        // When set, photo entries (gallery, files) hand the source back.
        onPickPhoto: ((fromFiles: Boolean) -> Unit)? = null,
        onPick: (pack: String?, name: String?) -> Unit
    ) {
        scope.launch {
            val packs = viewModel.availableIconPacks()
            // Each entry pairs its label with the action tapping it runs.
            // App icons come first after Default: the built-in source.
            val entries = buildList<Pair<String, () -> Unit>> {
                if (allowDefault) add(context.getString(R.string.option_default) to { onPick(null, null) })
                add(context.getString(R.string.icon_source_apps) to { showAppGrid(context, viewModel, onPick) })
                if (onPickPhoto != null) {
                    add(context.getString(R.string.photo_from_gallery) to { onPickPhoto(false) })
                    add(context.getString(R.string.photo_from_files) to { onPickPhoto(true) })
                }
                packs.forEach { pack ->
                    add(pack.label to { showGrid(context, scope, viewModel, pack.packageName, onPick) })
                }
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.change_icon)
                .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
                .setNegativeButton(R.string.cancel, null)
                .showTracked()
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
            showIconGrid(context, viewModel, IconGrid(packPackage, names), onPick)
        }
    }

    // Every installed app's own icon, A-Z by the app's name, unlabelled
    // (a long press names one). A work profile's copy of an app looks
    // the same, so each app shows once; private space apps stay off the
    // home screen, so they aren't offered.
    private fun showAppGrid(context: Context, viewModel: LauncherViewModel, onPick: (String?, String?) -> Unit) {
        val apps = appChoices(viewModel)
        val grid = IconGrid(AppIconSource.PACK, apps.map(AppIconSource::nameFor), apps.map { it.label })
        showIconGrid(context, viewModel, grid, onPick)
    }

    private fun appChoices(viewModel: LauncherViewModel): List<LaunchableApp> =
        viewModel.state.value.apps
            .filterNot { it.privateSpace }
            .distinctBy { "${it.packageName}/${it.className}" }

    // What the icon row shows for a pick from a grid: the pack drawable's
    // name, or the name of the app whose icon it is.
    fun pickLabel(viewModel: LauncherViewModel, pack: String?, name: String): String {
        val source = AppIconSource.appOf(name)?.takeIf { AppIconSource.isAppIcon(pack) } ?: return name
        return viewModel.state.value.apps.firstOrNull {
            it.packageName == source.packageName && it.className == source.className &&
                it.userSerial == source.userSerial
        }?.label ?: source.packageName
    }

    // One grid's icons: a pack's drawables, or the app icons by name.
    private class IconGrid(
        val packPackage: String,
        val names: List<String>,
        // Read out and shown on a long press; null for a pack's drawables.
        val labels: List<String>? = null
    )

    private fun showIconGrid(
        context: Context,
        viewModel: LauncherViewModel,
        grid: IconGrid,
        onPick: (String?, String?) -> Unit
    ) {
        val binding = SheetIconPickerBinding.inflate(LayoutInflater.from(context))
        val dialog = com.supernova.launcher.ui.Sheets.open(context)
        dialog.setContentView(binding.root)
        binding.iconGrid.layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, GRID_COLUMNS)
        val adapter = IconChoiceAdapter(viewModel, grid) { name ->
            onPick(grid.packPackage, name)
            dialog.dismiss()
        }
        binding.iconGrid.adapter = adapter
        // Filters as you type; a scan of names only, so a pack of
        // thousands keeps up. The sheet sits above the keyboard.
        binding.iconSearch.doAfterTextChanged { text ->
            adapter.show(IconSearch.filter(grid.names, grid.labels, text?.toString().orEmpty()))
            binding.iconGrid.scrollToPosition(0)
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    private const val GRID_COLUMNS = 5

    private class IconChoiceAdapter(
        private val viewModel: LauncherViewModel,
        private val grid: IconGrid,
        private val onPick: (String) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<IconChoiceAdapter.Holder>() {

        class Holder(val view: android.widget.ImageView) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_icon_choice, parent, false) as android.widget.ImageView
            return Holder(view)
        }

        // Grid indices on show: all of them, or a search's matches.
        private var shown: List<Int> = grid.names.indices.toList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun show(indices: List<Int>) {
            shown = indices
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val index = shown[position]
            val name = grid.names[index]
            // Lazy per-bind decode: the RecyclerView only materializes what
            // scrolls into view, so a 3,000-icon pack stays tractable.
            holder.view.setImageDrawable(viewModel.appCatalog().packDrawable(grid.packPackage, name))
            grid.labels?.getOrNull(index)?.let { label ->
                holder.view.contentDescription = label
                holder.view.tooltipText = label
            }
            holder.view.setOnClickListener { onPick(name) }
        }
    }
}
