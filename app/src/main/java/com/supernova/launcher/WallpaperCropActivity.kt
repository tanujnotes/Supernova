package com.supernova.launcher

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.databinding.ActivityWallpaperCropBinding
import com.supernova.launcher.ui.WallpaperCrop
import com.supernova.launcher.ui.dp
import com.supernova.launcher.ui.theme.ThemeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

// Sets an image picked from files as the wallpaper. The image fills the
// screen to be moved and zoomed into place, and Set wallpaper asks where it
// goes: the home screen, the lock screen, or both. The system's own crop
// screen set both with no choice on many phones (One UI's among them), so
// the launcher sets it itself.
class WallpaperCropActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWallpaperCropBinding

    // Whether the image, the right way up, is wider than tall: the decoder
    // may report its size before a photo's rotation is applied.
    private var landscape = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = runBlocking { appGraph.preferences.flow.first() }
        ThemeEngine.applyAccent(this, prefs.accentColor)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        // Edge to edge, so the frame is the whole screen the wallpaper fills.
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT),
        )
        binding = ActivityWallpaperCropBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.setWallpaper.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + dp(BUTTON_GAP_DP)
            }
            binding.scrim.updateLayoutParams { height = bars.bottom + dp(SCRIM_DP) }
            insets
        }
        binding.setWallpaper.setOnClickListener { askWhere(uri) }
        load(uri)
    }

    private fun load(uri: Uri) {
        val longSide = resources.displayMetrics.run { max(widthPixels, heightPixels) }
        lifecycleScope.launch {
            val image = withContext(Dispatchers.IO) {
                runLogged("load wallpaper image") { decodePreview(uri, longSide) }.getOrNull()
            }
            binding.progress.isVisible = false
            if (image == null) {
                Toast.makeText(this@WallpaperCropActivity, R.string.wallpaper_load_failed, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            landscape = image.width > image.height
            binding.cropView.setImage(image)
            binding.scrim.isVisible = true
            binding.setWallpaper.isVisible = true
        }
    }

    // The image to frame, at no less than the screen's resolution.
    private fun decodePreview(uri: Uri, longSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val size = info.size
            val rows = max(size.width, size.height).toFloat()
            decoder.setTargetSampleSize(WallpaperCrop.sampleSize(size.width, size.height, rows, longSide))
        }

    private fun askWhere(uri: Uri) {
        val targets = listOf(
            R.string.wallpaper_target_home to WallpaperManager.FLAG_SYSTEM,
            R.string.wallpaper_target_lock to WallpaperManager.FLAG_LOCK,
            R.string.wallpaper_target_both to (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wallpaper_set)
            .setItems(targets.map { getString(it.first) }.toTypedArray()) { _, which ->
                setWallpaper(uri, targets[which].second)
            }
            .show()
    }

    private fun setWallpaper(uri: Uri, which: Int) {
        val framed = binding.cropView.crop() ?: return
        // A home screen that scrolls its wallpaper gets the image beyond the
        // framed part to scroll across; the lock screen, which doesn't
        // scroll, gets just what is framed, so Home and lock screen sets the
        // two apart.
        val homeOffset = intent.getFloatExtra(EXTRA_HOME_OFFSET, Float.NaN)
        val home = if (which and WallpaperManager.FLAG_SYSTEM != 0 && !homeOffset.isNaN()) {
            WallpaperCrop.widenForScrolling(framed, homeOffset)
        } else framed
        val pieces = if (home == framed) listOf(which to framed) else buildList {
            if (which and WallpaperManager.FLAG_LOCK != 0) add(WallpaperManager.FLAG_LOCK to framed)
            add(WallpaperManager.FLAG_SYSTEM to home)
        }
        val frameHeight = binding.cropView.height
        val landscape = landscape
        binding.setWallpaper.isEnabled = false
        binding.progress.isVisible = true
        val manager = WallpaperManager.getInstance(this)
        lifecycleScope.launch {
            val done = withContext(Dispatchers.IO) {
                runLogged("set wallpaper") {
                    check(manager.isSetWallpaperAllowed) { "setting the wallpaper is not allowed" }
                    val whole = decodeFull(uri, framed.height, frameHeight, landscape)
                    for ((flags, crop) in pieces) {
                        val bitmap = cut(whole, crop, frameHeight)
                        manager.setBitmap(bitmap, null, true, flags)
                        if (bitmap !== whole) bitmap.recycle()
                    }
                    whole.recycle()
                }.isSuccess
            }
            if (done) {
                finish()
                return@launch
            }
            binding.progress.isVisible = false
            binding.setWallpaper.isEnabled = true
            Toast.makeText(this@WallpaperCropActivity, R.string.wallpaper_set_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // The image decoded afresh, at the resolution the framed part needs to
    // fill the frame's height. cropHeight is the framed part's share of the
    // image's height.
    private fun decodeFull(uri: Uri, cropHeight: Float, frameHeight: Int, landscape: Boolean): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val size = info.size
            val upright = (size.width > size.height) == landscape
            val width = if (upright) size.width else size.height
            val height = if (upright) size.height else size.width
            decoder.setTargetSampleSize(WallpaperCrop.sampleSize(width, height, cropHeight * height, frameHeight))
        }

    // A crop of the decoded image, scaled to the frame's height.
    private fun cut(whole: Bitmap, crop: WallpaperCrop.Box, frameHeight: Int): Bitmap {
        val left = (crop.left * whole.width).roundToInt().coerceIn(0, whole.width - 1)
        val top = (crop.top * whole.height).roundToInt().coerceIn(0, whole.height - 1)
        val right = (crop.right * whole.width).roundToInt().coerceIn(left + 1, whole.width)
        val bottom = (crop.bottom * whole.height).roundToInt().coerceIn(top + 1, whole.height)
        val scale = frameHeight.toFloat() / (bottom - top)
        val matrix = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(whole, left, top, right - left, bottom - top, matrix, true)
    }

    companion object {
        // Where the home page sits across a scrolling wallpaper (0..1), from
        // WallpaperScrollMode.offset; absent when the wallpaper doesn't
        // scroll.
        const val EXTRA_HOME_OFFSET = "com.supernova.launcher.extra.HOME_OFFSET"

        private const val BUTTON_GAP_DP = 24
        private const val SCRIM_DP = 160
    }
}
