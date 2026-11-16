package com.supernova.launcher.ui

import android.content.Context
import android.widget.Toast
import com.supernova.launcher.R
import com.supernova.launcher.data.DrawerGroup
import com.supernova.launcher.data.GalleryIconCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A drawer tab's icon, from its page in Drawer groups: Default (its glyph or
// letter), an app's icon, a pack icon or a photo. Stored as picked, at once;
// a tab draws its picture as it is, never reshaped.
object TabIconPicker {

    fun show(
        context: Context,
        scope: CoroutineScope,
        viewModel: LauncherViewModel,
        group: DrawerGroup,
        pickPhoto: PhotoPicker
    ) {
        fun save(pack: String?, name: String?, photo: String?) {
            viewModel.setGroupIcon(
                group.name,
                group.copy(iconPack = pack, iconName = name, iconBase64 = photo, iconRaw = false)
            )
        }
        val onPhoto = { fromFiles: Boolean ->
            pickPhoto(fromFiles) { uri ->
                if (uri != null) {
                    scope.launch {
                        val encoded = withContext(Dispatchers.IO) {
                            GalleryIconCodec.encode(context.applicationContext, uri)
                        }
                        if (encoded == null) {
                            Toast.makeText(context, R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
                        } else {
                            save(null, null, encoded)
                        }
                    }
                }
            }
        }
        IconPickerSheet.show(context, scope, viewModel, allowDefault = true, onPickPhoto = onPhoto) { pack, name ->
            save(pack, name, null)
        }
    }
}
