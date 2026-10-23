package com.supernova.launcher.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.supernova.launcher.data.CustomShapeSpec
import com.supernova.launcher.data.GlassPainter
import com.supernova.launcher.data.IconGlass
import com.supernova.launcher.data.IconShape
import com.supernova.launcher.data.LauncherPrefs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

enum class FolderPreviewStyle {
    GRID,
    STACK,
    FAN,
    LINE,
    RADIAL,
    RADIAL_DENSE,
    GRID_3X3;

    companion object {
        fun fromName(name: String): FolderPreviewStyle =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GRID
    }
}

// Folder preview: a plate with member icons arranged per the chosen style -
// tiled grids, offset stack, rotated fan, a single row, or icon rings. The
// plate clips to `shape` when one is set (plateShape: the folder's own pick,
// else the icons'), and can carry an accent outline; `clipMembers` keeps
// icons inside the plate. It is dressed as the icons are: a glass pane for
// iconGlass, the icons' drop shadow for iconShadow.
class FolderIconDrawable(
    private val members: List<Drawable>,
    private val sizePx: Int,
    private val style: FolderPreviewStyle = FolderPreviewStyle.GRID,
    plateColor: Int = DEFAULT_PLATE_COLOR,
    private val shape: IconShape? = null,
    private val customSpec: CustomShapeSpec? = null,
    outlineColor: Int = 0,
    private val outlineWidthPx: Float = 0f,
    private val clipMembers: Boolean = true,
    // First member drawn large and centred, the next three small along the
    // bottom; overrides the arrangement of every style.
    private val prioritizeFirst: Boolean = false,
    // The plate as glass, the pane an icon with this colour's background
    // gets; OFF fills it with plateColor.
    private val glass: IconGlass = IconGlass.OFF,
    // The icons' drop shadow, cast by the plate's outline.
    private val shadow: Boolean = false
) : Drawable() {

    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = plateColor
    }
    // The plate's pane takes the colour without its alpha; the glass sets
    // how much of it shows.
    private val paneColor = plateColor or OPAQUE_ALPHA

    // The plate, its shadow and rim, painted once per size and drawn from
    // here every frame: a blur and a pane are too slow for draw(). It runs
    // `margin` past the bounds on every side, room for the shadow.
    private var plate: Bitmap? = null
    private var margin = 0
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val outlinePaint: Paint? = if (outlineColor != 0 && outlineWidthPx > 0f) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = outlineColor
            this.style = Paint.Style.STROKE
            strokeWidth = outlineWidthPx
        }
    } else {
        null
    }

    // draw() runs every frame; slice the member lists once.
    private val shownFour = members.take(4)
    private val shownThree = members.take(3)
    private val shownSeven = members.take(7)
    private val shownNine = members.take(9)

    // Plate path rebuilt only when bounds change, never in draw().
    private var platePath = Path()

    override fun onBoundsChange(bounds: Rect) {
        val size = bounds.width().toFloat()
        val shaped = if (shape == IconShape.SYSTEM) systemMask(bounds.width()) else shape?.pathFor(size, customSpec)
        platePath = if (shaped != null) {
            shaped.apply { offset(bounds.left.toFloat(), bounds.top.toFloat()) }
        } else {
            Path().apply {
                val radius = size * 0.24f
                addRoundRect(
                    RectF(
                        bounds.left.toFloat(), bounds.top.toFloat(),
                        bounds.right.toFloat(), bounds.bottom.toFloat()
                    ),
                    radius, radius, Path.Direction.CW
                )
            }
        }
        plate?.recycle()
        plate = null
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val painted = plate ?: paintPlate().also { plate = it }
        canvas.drawBitmap(
            painted, (bounds.left - margin).toFloat(), (bounds.top - margin).toFloat(), bitmapPaint
        )

        if (members.isNotEmpty()) {
            val save = if (clipMembers) {
                canvas.save().also { canvas.clipPath(platePath) }
            } else {
                -1
            }
            drawMembers(canvas)
            if (save >= 0) canvas.restoreToCount(save)
        }

        outlinePaint?.let { canvas.drawPath(platePath, it) }
    }

    private fun drawMembers(canvas: Canvas) {
        if (prioritizeFirst) {
            drawPrioritized(canvas)
            return
        }
        when (style) {
            FolderPreviewStyle.GRID -> drawGrid(canvas)
            FolderPreviewStyle.STACK -> drawStack(canvas)
            FolderPreviewStyle.FAN -> drawFan(canvas)
            FolderPreviewStyle.LINE -> drawLine(canvas)
            FolderPreviewStyle.RADIAL -> drawRadial(canvas, shownSeven, ringScale = 0.26f, centerScale = 0.34f)
            FolderPreviewStyle.RADIAL_DENSE -> drawRadial(canvas, shownNine, ringScale = 0.22f, centerScale = 0.30f)
            FolderPreviewStyle.GRID_3X3 -> drawGrid3x3(canvas)
        }
    }

    // Shadow first, then the plate over it: plateColor, or the glass pane and
    // its rim. The shadow is kept only outside the plate, as a glass icon's
    // is - seen through a pane or a translucent plate it just muddies it. It
    // is the icons' (IconRenderer.withShadow): 30% black, blurred 2dp and
    // dropped 1dp at the 48dp base size, scaled with the plate.
    private fun paintPlate(): Bitmap {
        val size = bounds.width()
        val local = Path(platePath).apply { offset(-bounds.left.toFloat(), -bounds.top.toFloat()) }
        val blur = size * SHADOW_BLUR / BASE_SIZE
        val drop = size * SHADOW_DROP / BASE_SIZE
        margin = if (shadow) ceil(blur + drop).toInt() else 0
        val bitmap = createBitmap(size + 2 * margin, bounds.height() + 2 * margin)
        val canvas = Canvas(bitmap)
        canvas.withTranslation(margin.toFloat(), margin.toFloat()) {
            if (shadow && blur > 0f) {
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = SHADOW_COLOR
                    maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
                }
                withTranslation(0f, drop) { drawPath(local, shadowPaint) }
                drawPath(local, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = CLEAR })
            }
            if (glass == IconGlass.OFF) {
                drawPath(local, platePaint)
            } else {
                withClip(local) { painter.drawPane(this, glass, paneColor, size, bounds.height()) }
                painter.drawEdge(this, local, glass, size, bounds.height())
            }
        }
        return bitmap
    }

    // The System shape: the platform's own icon mask at this size.
    private fun systemMask(size: Int): Path {
        val icon = AdaptiveIconDrawable(Color.BLACK.toDrawable(), Color.BLACK.toDrawable())
        icon.setBounds(0, 0, size, size)
        return Path(icon.iconMask)
    }

    // folderPrioritizeFirst pref: the first member fills most of the plate,
    // the next three sit in a small row along the bottom edge.
    private fun drawPrioritized(canvas: Canvas) {
        val size = bounds.width().toFloat()
        val first = members.first()
        val mainSize = (size * 0.62f).toInt()
        val mainLeft = (bounds.left + (size - mainSize) / 2f).toInt()
        val mainTop = (bounds.top + size * 0.08f).toInt()
        first.setBounds(mainLeft, mainTop, mainLeft + mainSize, mainTop + mainSize)
        first.draw(canvas)

        val rest = members.drop(1).take(3)
        if (rest.isEmpty()) return
        val miniSize = (size * 0.20f).toInt()
        val gap = size * 0.04f
        val rowWidth = rest.size * miniSize + (rest.size - 1) * gap
        var x = bounds.left + (size - rowWidth) / 2f
        val y = (bounds.bottom - size * 0.08f - miniSize).toInt()
        rest.forEach { drawable ->
            drawable.setBounds(x.toInt(), y, x.toInt() + miniSize, y + miniSize)
            drawable.draw(canvas)
            x += miniSize + gap
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val padding = bounds.width() * 0.12f
        val gap = bounds.width() * 0.04f
        val cell = ((bounds.width() - (padding * 2) - gap) / 2f).toInt()
        shownFour.forEachIndexed { index, drawable ->
            val col = index % 2
            val row = index / 2
            val left = (bounds.left + padding + col * (cell + gap)).toInt()
            val top = (bounds.top + padding + row * (cell + gap)).toInt()
            drawable.setBounds(left, top, left + cell, top + cell)
            drawable.draw(canvas)
        }
    }

    // Up to three icons overlapping toward the bottom-right, first on top.
    private fun drawStack(canvas: Canvas) {
        val iconSize = (bounds.width() * 0.52f).toInt()
        val step = (bounds.width() * 0.12f).toInt()
        val shown = shownThree
        val start = (bounds.width() - iconSize - step * (shown.size - 1)) / 2
        shown.asReversed().forEachIndexed { reversedIndex, drawable ->
            val index = shown.size - 1 - reversedIndex
            val left = bounds.left + start + index * step
            val top = bounds.top + start + index * step
            drawable.setBounds(left, top, left + iconSize, top + iconSize)
            drawable.draw(canvas)
        }
    }

    // Up to three icons splayed like held cards.
    private fun drawFan(canvas: Canvas) {
        val iconSize = (bounds.width() * 0.5f).toInt()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val shown = shownThree
        val angles = when (shown.size) {
            1 -> listOf(0f)
            2 -> listOf(-14f, 14f)
            else -> listOf(-22f, 0f, 22f)
        }
        shown.forEachIndexed { index, drawable ->
            canvas.save()
            canvas.rotate(angles[index], cx, cy + bounds.height() * 0.3f)
            val left = (cx - iconSize / 2f).toInt()
            val top = (cy - iconSize / 2f).toInt()
            drawable.setBounds(left, top, left + iconSize, top + iconSize)
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    // Up to three icons in a centered row.
    private fun drawLine(canvas: Canvas) {
        val shown = shownThree
        val gap = bounds.width() * 0.06f
        val iconSize = ((bounds.width() * 0.8f - gap * (shown.size - 1)) / shown.size).toInt()
        val totalWidth = iconSize * shown.size + gap * (shown.size - 1)
        var left = bounds.left + ((bounds.width() - totalWidth) / 2f)
        val top = (bounds.exactCenterY() - iconSize / 2f).toInt()
        shown.forEach { drawable ->
            drawable.setBounds(left.toInt(), top, left.toInt() + iconSize, top + iconSize)
            drawable.draw(canvas)
            left += iconSize + gap
        }
    }

    // One center icon with the rest evenly spaced on a ring around it,
    // starting at 12 o'clock. Positions are trig-computed per icon; nothing
    // allocates.
    private fun drawRadial(canvas: Canvas, shown: List<Drawable>, ringScale: Float, centerScale: Float) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val centerSize = (bounds.width() * centerScale).toInt()
        val first = shown.first()
        first.setBounds(
            (cx - centerSize / 2f).toInt(), (cy - centerSize / 2f).toInt(),
            (cx + centerSize / 2f).toInt(), (cy + centerSize / 2f).toInt()
        )
        first.draw(canvas)

        val ring = shown.size - 1
        if (ring <= 0) return
        val ringSize = (bounds.width() * ringScale).toInt()
        val radius = bounds.width() * 0.335f
        for (index in 0 until ring) {
            val angle = -Math.PI / 2 + index * (2 * Math.PI / ring)
            val ix = cx + (radius * cos(angle)).toFloat()
            val iy = cy + (radius * sin(angle)).toFloat()
            val drawable = shown[index + 1]
            drawable.setBounds(
                (ix - ringSize / 2f).toInt(), (iy - ringSize / 2f).toInt(),
                (ix + ringSize / 2f).toInt(), (iy + ringSize / 2f).toInt()
            )
            drawable.draw(canvas)
        }
    }

    // Nine mini icons tiled 3 x 3.
    private fun drawGrid3x3(canvas: Canvas) {
        val padding = bounds.width() * 0.10f
        val gap = bounds.width() * 0.03f
        val cell = ((bounds.width() - (padding * 2) - gap * 2) / 3f).toInt()
        shownNine.forEachIndexed { index, drawable ->
            val col = index % 3
            val row = index / 3
            val left = (bounds.left + padding + col * (cell + gap)).toInt()
            val top = (bounds.top + padding + row * (cell + gap)).toInt()
            drawable.setBounds(left, top, left + cell, top + cell)
            drawable.draw(canvas)
        }
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val DEFAULT_PLATE_COLOR = 0x59FFFFFF

        // The plate's shape: the folder's own pick, else the icons' -
        // "" (Default) follows the icon shape.
        fun plateShape(folderIconShape: String, prefs: LauncherPrefs): IconShape =
            IconShape.fromName(folderIconShape.ifEmpty { prefs.iconShape })

        fun glassFor(prefs: LauncherPrefs): IconGlass = IconGlass.fromName(prefs.iconGlass)

        private const val OPAQUE_ALPHA = 0xFF000000.toInt()
        private const val SHADOW_COLOR = 0x4D000000
        private const val BASE_SIZE = 48f
        private const val SHADOW_BLUR = 2f
        private const val SHADOW_DROP = 1f
        private val CLEAR = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        // Stateless apart from its grain tile, which every plate can share.
        private val painter = GlassPainter()
    }
}
