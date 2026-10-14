package com.supernova.launcher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.supernova.launcher.R
import com.supernova.launcher.data.GalleryIconCodec
import com.supernova.launcher.data.LauncherItem
import com.supernova.launcher.databinding.SheetAppEditBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A placed folder's Edit sheet, the edit dialog for folders: name, icon,
// Swipe to open folder, and the swipe up and down actions. It is the app Edit
// sheet's layout with the app-only rows hidden. Name and icon commit with
// Save, like the app sheet's fields; the switch and the swipe actions commit
// at once, as the app sheet's swipe rows do.
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
        var chosenPack = originalPack
        var chosenIcon = originalIcon
        var chosenPhoto = originalPhoto

        binding.editTitle.text = originalName.ifBlank { context.getString(R.string.folder) }
        binding.editLabel.setText(originalName)
        binding.hideLabelRow.visibility = View.GONE
        binding.shapeRow.visibility = View.GONE
        binding.variantRow.visibility = View.GONE
        binding.resetButton.visibility = View.GONE

        fun renderIcon() {
            binding.iconValue.text = when {
                chosenPhoto != null -> context.getString(R.string.choose_photo)
                chosenIcon != null -> chosenIcon
                else -> context.getString(R.string.option_default)
            }
        }
        renderIcon()

        binding.iconRow.setOnClickListener {
            IconPickerSheet.show(context, scope, viewModel, allowDefault = true) { pack, name ->
                chosenPack = pack
                chosenIcon = name
                // A pack icon (or a reset to default) replaces any photo.
                chosenPhoto = null
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
                    chosenPhoto = encoded
                    chosenPack = null
                    chosenIcon = null
                    renderIcon()
                }
            }
        }

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
        binding.swipeToOpenRow.visibility = View.VISIBLE
        binding.swipeUpRow.visibility = View.VISIBLE
        binding.swipeDownRow.visibility = View.VISIBLE
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
            if (chosenPack != originalPack || chosenIcon != originalIcon || chosenPhoto != originalPhoto) {
                if (chosenPhoto != null) {
                    viewModel.setFolderIcon(folder.id, null, null, chosenPhoto)
                } else {
                    viewModel.setFolderIcon(folder.id, chosenPack, chosenIcon)
                }
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private const val DISABLED_ALPHA = 0.5f
}
