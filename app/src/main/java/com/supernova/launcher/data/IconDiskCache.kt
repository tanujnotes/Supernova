package com.supernova.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toDrawable
import com.supernova.launcher.runLogged
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

// Rendered icons kept on disk, so a launcher whose process was stopped
// (overnight, say) reads its icons back instead of drawing every one again
// from each app's package: about 12 ms an icon on the test phone, against a
// millisecond or two to read one.
//
// One folder per icon configuration (`signature`: shape, pack, shadow, glass,
// resolution and the rest); a new configuration starts an empty folder and
// the older ones are deleted, all but the one before it: a day/night icon
// pack's dark and light icons are two configurations, and the launcher turns
// dark and light every day (#288). Each file is one cache key, headed by `stamp`:
// what the icon was drawn from (the app's installed package, its edit). A
// stamp that no longer matches is a miss, and the next render overwrites it.
class IconDiskCache(context: Context) {
    private val root = File(context.cacheDir, "icons")
    private val resources = context.resources
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "icon-disk").apply { isDaemon = true }
    }

    @Volatile
    private var current: File? = null

    // The icon cached under `key` for this configuration and stamp, or null.
    fun read(signature: String, key: String, stamp: String): Drawable? {
        val file = File(folderFor(signature), name(key))
        if (!file.exists()) return null
        return runLogged("read cached icon") {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != stamp) return@use null
                val margin = input.readInt()
                val bitmap = BitmapFactory.decodeStream(input) ?: return@use null
                if (margin > 0) ShadowedIconDrawable(bitmap, margin) else bitmap.toDrawable(resources)
            }
        }.getOrNull()
    }

    // Saves a rendered icon behind the caller, on a thread of its own. Only
    // the two kinds a render produces are kept; anything else isn't.
    fun write(signature: String, key: String, stamp: String, icon: Drawable) {
        val (bitmap, margin) = when (icon) {
            is ShadowedIconDrawable -> icon.bitmap to icon.margin
            is BitmapDrawable -> (icon.bitmap ?: return) to 0
            else -> return
        }
        val folder = folderFor(signature)
        writer.execute {
            runLogged("write cached icon") {
                folder.mkdirs()
                val file = File(folder, name(key))
                val partial = File(folder, "${file.name}.part")
                DataOutputStream(partial.outputStream().buffered()).use { output ->
                    output.writeUTF(stamp)
                    output.writeInt(margin)
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
                }
                partial.renameTo(file)
            }
        }
    }

    // Labs' Clear icon cache.
    fun clear() {
        writer.execute { runLogged("clear cached icons") { root.deleteRecursively() } }
    }

    private fun folderFor(signature: String): File {
        val folder = File(root, hash(signature))
        if (current != folder) {
            current = folder
            // Icons drawn for a configuration no longer in use go, all but
            // the last one used before this, to switch back to. A folder's
            // time is when it was last switched to or written into.
            writer.execute {
                runLogged("prune cached icons") {
                    folder.mkdirs()
                    folder.setLastModified(System.currentTimeMillis())
                    root.listFiles()?.filter { it != folder }?.sortedByDescending { it.lastModified() }
                        ?.drop(1)?.forEach { it.deleteRecursively() }
                }
            }
        }
        return folder
    }

    private fun name(key: String): String = hash(key)

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        // PNG is lossless whatever the quality.
        const val PNG_QUALITY = 100
    }
}
