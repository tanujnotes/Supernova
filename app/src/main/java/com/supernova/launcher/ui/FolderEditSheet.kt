package com.supernova.launcher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.GalleryIconCodec
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.databinding.SheetAppEditBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A placed folder's Edit sheet, the edit dialog for folders: name, icon
// (and Reshape, for a custom one), Swipe to open folder, and the swipe up and
// down actions. It is the app Edit sheet's layout with the app-only rows
// hidden. Name, icon and Reshape commit with Save, like the app sheet's
// fields; the swipe switch and actions commit at once, as the app sheet's
// swipe rows do.
object FolderEditSheet {

    fun show(
        context: Context,
        scope: CoroutineScope,
        viewModel: LauncherViewModel,
        folder: LauncherItem,
        // Host-provided photo picker (the ActivityResult launcher is
        // registered on the activity).
        pickPhoto: ((android.net.Uri?) -> Unit) -> Unit,
        // Opens the host's swipe action picker, calling back with the chosen
        // label (null = cleared) so the row can refresh without racing the
        // async model write.
        onEditSwipe: (down: Boolean, onPicked: (String?) -> Unit) -> Unit
    ) {
        val binding = SheetAppEditBinding.inflate(LayoutInflater.from(context))
        val dialog = Sheets.open(context)
        dialog.setContentView(binding.root)

        val originalName = folder.label
        val originalPack = folder.customIconPack
        val originalIcon = folder.customIconName
        val originalPhoto = folder.iconBase64
        val originalRaw = folder.iconRaw
        val icon = IconChoice(originalPack, originalIcon, originalPhoto, originalRaw)

        binding.editTitle.text = originalName.ifBlank { context.getString(R.string.folder) }
        binding.editLabel.setText(originalName)
        hideAppRows(binding)
        bindIconRows(context, scope, viewModel, binding, icon, pickPhoto)

        // Read live, so a re-open shows what a just-committed pick left.
        fun live(): LauncherItem? = viewModel.state.value.model.items.firstOrNull { it.id == folder.id }
        var swipeToOpen = live()?.swipeToOpen ?: folder.swipeToOpen
        var upLabel = live()?.swipeLabel
        var downLabel = live()?.swipeLabelDown
        fun renderSwipes() {
            val none = context.getString(R.string.swipe_none)
            // While the switch is on the swipe up is the folder's own; the
            // action it held is kept and comes back when the switch goes off.
            binding.swipeUpValue.text = if (swipeToOpen) context.getString(R.string.open_folder) else upLabel ?: none
            binding.swipeUpRow.isEnabled = !swipeToOpen
            binding.swipeUpRow.alpha = if (swipeToOpen) DISABLED_ALPHA else 1f
            binding.swipeDownValue.text = downLabel ?: none
        }
        // A locked desktop keeps the name and icon editable but hides the
        // swipe rows, as an app's Edit does.
        val swipeRows = if (viewModel.state.value.prefs.desktopLocked) View.GONE else View.VISIBLE
        binding.swipeToOpenRow.visibility = swipeRows
        binding.swipeUpRow.visibility = swipeRows
        binding.swipeDownRow.visibility = swipeRows
        binding.swipeToOpenSwitch.isChecked = swipeToOpen
        renderSwipes()
        binding.swipeToOpenRow.setOnClickListener { binding.swipeToOpenSwitch.toggle() }
        binding.swipeToOpenSwitch.setOnCheckedChangeListener { _, checked ->
            swipeToOpen = checked
            viewModel.setFolderSwipeToOpen(folder.id, checked)
            renderSwipes()
        }
        binding.swipeUpRow.setOnClickListener {
            onEditSwipe(false) { picked -> upLabel = picked; renderSwipes() }
        }
        binding.swipeDownRow.setOnClickListener {
            onEditSwipe(true) { picked -> downLabel = picked; renderSwipes() }
        }

        binding.saveButton.setOnClickListener {
            val typed = binding.editLabel.text?.toString().orEmpty().trim()
            if (typed.isNotEmpty() && typed != originalName) viewModel.renameFolder(folder.id, typed)
            if (icon.pack != originalPack || icon.name != originalIcon || icon.photo != originalPhoto) {
                if (icon.photo != null) {
                    viewModel.setFolderIcon(folder.id, null, null, icon.photo)
                } else {
                    viewModel.setFolderIcon(folder.id, icon.pack, icon.name)
                }
            }
            if (icon.savedRaw != originalRaw) viewModel.setFolderIconRaw(folder.id, icon.savedRaw)
            dialog.dismiss()
        }

        dialog.show()
    }

    // A drawer folder's Edit: its name and icon, the same rows as a placed
    // folder's. Its apps have no home screen icon to swipe.
    fun showDrawerFolder(
        context: Context,
        scope: CoroutineScope,
        viewModel: LauncherViewModel,
        group: DrawerGroup,
        pickPhoto: ((android.net.Uri?) -> Unit) -> Unit
    ) {
        val binding = SheetAppEditBinding.inflate(LayoutInflater.from(context))
        val dialog = Sheets.open(context)
        dialog.setContentView(binding.root)

        val icon = IconChoice(group.iconPack, group.iconName, group.iconBase64, group.iconRaw)
        val original = icon.copy()
        binding.editTitle.text = group.name
        binding.editLabel.setText(group.name)
        hideAppRows(binding)
        binding.swipeToOpenRow.visibility = View.GONE
        binding.swipeUpRow.visibility = View.GONE
        binding.swipeDownRow.visibility = View.GONE
        bindIconRows(context, scope, viewModel, binding, icon, pickPhoto)

        binding.saveButton.setOnClickListener {
            val typed = binding.editLabel.text?.toString().orEmpty().trim()
            val name = if (typed.isEmpty()) group.name else viewModel.renameGroup(group, typed)
            if (name == null) {
                Toast.makeText(context, R.string.group_name_taken, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (icon.copy(raw = icon.savedRaw) != original) {
                viewModel.setGroupIcon(
                    name,
                    group.copy(
                        iconPack = icon.pack, iconName = icon.name, iconBase64 = icon.photo, iconRaw = icon.savedRaw
                    )
                )
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    // What the icon rows hold until Save.
    private data class IconChoice(var pack: String?, var name: String?, var photo: String?, var raw: Boolean) {
        val isCustom: Boolean get() = photo != null || name != null

        // Back on the default icon, a later custom one starts reshaped.
        val savedRaw: Boolean get() = raw && isCustom
    }

    private fun hideAppRows(binding: SheetAppEditBinding) {
        binding.hideLabelRow.visibility = View.GONE
        binding.shapeRow.visibility = View.GONE
        binding.variantRow.visibility = View.GONE
        binding.resetButton.visibility = View.GONE
    }

    private fun bindIconRows(
        context: Context,
        scope: CoroutineScope,
        viewModel: LauncherViewModel,
        binding: SheetAppEditBinding,
        icon: IconChoice,
        pickPhoto: ((android.net.Uri?) -> Unit) -> Unit
    ) {
        fun renderIcon() {
            binding.iconValue.text = when {
                icon.photo != null -> context.getString(R.string.choose_photo)
                icon.name != null -> icon.name
                else -> context.getString(R.string.option_default)
            }
            // The default icon is the folder's own preview: nothing to fit.
            binding.reshapeRow.visibility = if (icon.isCustom) View.VISIBLE else View.GONE
        }
        renderIcon()
        binding.reshapeSwitch.isChecked = !icon.raw
        binding.reshapeRow.setOnClickListener { binding.reshapeSwitch.toggle() }
        binding.reshapeSwitch.setOnCheckedChangeListener { _, checked -> icon.raw = !checked }

        binding.iconRow.setOnClickListener {
            IconPickerSheet.show(context, scope, viewModel, allowDefault = true) { pack, name ->
                icon.pack = pack
                icon.name = name
                // A pack icon (or a reset to default) replaces any photo.
                icon.photo = null
                renderIcon()
            }
        }
        binding.photoRow.setOnClickListener {
            pickPhoto { uri ->
                if (uri == null) return@pickPhoto
                scope.launch {
                    val encoded = withContext(Dispatchers.IO) {
                        GalleryIconCodec.encode(context.applicationContext, uri)
                    }
                    if (encoded == null) {
                        Toast.makeText(context, R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    icon.photo = encoded
                    icon.pack = null
                    icon.name = null
                    renderIcon()
                }
            }
        }
    }

    private const val DISABLED_ALPHA = 0.5f
}
