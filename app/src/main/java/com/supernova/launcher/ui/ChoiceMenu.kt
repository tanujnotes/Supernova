package com.supernova.launcher.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.supernova.launcher.R
import com.supernova.launcher.ui.theme.ResolvedSurface
import com.supernova.launcher.ui.theme.SurfaceShapes

// A choice opens where the setting is: a small menu at the row you
// tapped, not a dialog in the middle of a dimmed screen. This is that menu -
// the options, the current one tinted and ticked, and nothing else. No title, because
// the row it hangs off already carries one, and no Cancel, because tapping
// away is how a menu is dismissed. Used by the settings screen and by the
// per-app Edit sheet, which asks the same kind of question about a row.
//
// Deliberately not SupernovaPopupMenu: that one is built non-focusable and
// non-touch-modal so a long-press drag can keep streaming through it, which is
// exactly wrong for a settings menu, and its rows are icon-led. The look is
// kept in step by resolving the same POPUP surface.
object ChoiceMenu {

    // How much of the screen a long list may cover before it scrolls inside
    // the menu instead of growing past the anchor.
    private const val MAX_HEIGHT_FRACTION = 0.6f
    private const val MIN_WIDTH_DP = 236
    private const val CORNER_DP = 16
    private const val ROW_HEIGHT_DP = 48
    private const val LIST_PADDING_DP = 6
    private const val MEDIUM_FAMILY = "sans-serif-medium"
    // The tick's slot: the settings rows' 48dp control column.
    private const val TICK_SLOT_DP = 48
    // The current option's accent wash: 10% on a light menu, 18% on a dark.
    private const val CHECKED_ALPHA_LIGHT = 0x1A
    private const val CHECKED_ALPHA_DARK = 0x2E
    private const val OPAQUE = 0xFF000000.toInt()
    private const val HALF = 0.5

    // The card's lift, and the room the window has to leave around it for the
    // shadow that lift casts. A PopupWindow is sized to its content, so a
    // shadow with no padding to fall into is simply clipped away - which is
    // why setting elevation on the window itself drew nothing.
    //
    // The gutter is generous against the lift on purpose. A material shadow
    // reaches about twice its elevation below the card once the key light's
    // offset and blur are added together, and a gutter that merely looks
    // roomy cuts the gradient off mid-fade - a hard line under the card, which
    // is worse than no shadow at all. It costs nothing to overshoot: the
    // gutter is transparent, and the window is free to hang off the screen.
    private const val ELEVATION_DP = 8
    private const val SHADOW_PAD_DP = 22

    fun show(
        anchor: View,
        // Where the finger went down, in window coordinates. A settings row
        // spans the screen and its value sits at the far end of it, so a menu
        // pinned to the row's leading edge opens nowhere near the thing that
        // was pressed. The menu follows the touch instead, and is pushed back
        // inside the window when the touch is close to an edge.
        touchX: Float,
        surface: ResolvedSurface,
        labels: List<CharSequence>,
        // Index of the option currently in force, or -1 when the menu is a
        // plain list of things to do rather than a choice with a value.
        selected: Int,
        animate: Boolean = true,
        // A settings row opens its menu against its card's end instead of at
        // the touch, so the tick lands in the column of the chevron that
        // opened it: the row spans its card, so its end is the card's.
        alignToAnchorEnd: Boolean = false,
        onPick: (Int) -> Unit
    ) {
        val context = anchor.context
        val density = context.resources.displayMetrics.density

        // The keyboard goes first, so the menu is measured against the layout
        // the user is left with. Placement below reads the layout as it
        // stands, which holds because no window that opens this menu resizes
        // for the keyboard: the launcher is adjustNothing, and in Settings
        // only search mode raises a keyboard and its rows navigate rather than
        // open menus. A window that did resize would need this to wait for the
        // resize to land.
        anchor.hideKeyboardForWindow()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Wide enough that a two-word option doesn't wrap, and at least as
            // wide as the row it drops out of so it reads as belonging to it.
            minimumWidth = maxOf(context.dp(MIN_WIDTH_DP), anchor.width / 2)
            setPadding(0, context.dp(LIST_PADDING_DP), 0, context.dp(LIST_PADDING_DP))
            background = SurfaceShapes.background(
                surface.surface,
                surface.cornerRadiiDp?.let { radii -> FloatArray(4) { radii[it] * density } },
                surface.cornerCuts,
                context.dp(CORNER_DP).toFloat()
            )
            clipToOutline = true
        }

        // The card carries the background, the rounded outline and the lift,
        // so the shadow follows the menu's actual shape.
        val card = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            background = content.background
            clipToOutline = true
            elevation = ELEVATION_DP * density
            addView(
                content.apply { background = null },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // A transparent gutter for the shadow to fall into. Without it the
        // window ends at the card's edge and clips the shadow off.
        val pad = context.dp(SHADOW_PAD_DP)
        val shadowRoom = android.widget.FrameLayout(context).apply {
            clipToPadding = false
            clipChildren = false
            setPadding(pad, pad, pad, pad)
            addView(
                card,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val popup = PopupWindow(
            shadowRoom,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            // Focusable: a settings menu is modal, so back closes it and an
            // outside tap doesn't also press whatever is under it.
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(0))
            isOutsideTouchable = true
            // Lets the window extend past the screen edge, so the card can sit
            // where it was asked to with its shadow gutter hanging off. Without
            // it the system pushes an oversized window back inside and drags
            // the card off its mark by the gutter's width.
            isClippingEnabled = false
            animationStyle = if (animate) R.style.FolderPopupAnimation else 0
        }

        labels.forEachIndexed { index, label ->
            content.addView(row(context, surface, label, index == selected, density) {
                popup.dismiss()
                onPick(index)
            })
        }

        // Placed by absolute window position rather than as a drop-down: the
        // menu has to answer to the touch horizontally and to the row
        // vertically, and showAsDropDown offers neither - it never flips a
        // too-tall popup above its anchor, and its offsets mirror themselves
        // under a right-to-left layout.
        val window = anchor.rootView
        val location = IntArray(2)
        anchor.getLocationInWindow(location)

        // A menu belongs against its row, so it is never taller than the room
        // on the side it opens towards - it takes the roomier side and scrolls
        // inside whatever that side has. Sizing it first and placing it after
        // is what stranded a long list: too tall for either side, it overflowed
        // the screen, got clamped to the top, and ended up floating nowhere
        // near the row it came from.
        val roomBelow = window.height - (location[1] + anchor.height) - pad
        val roomAbove = location[1] - pad
        val below = roomBelow >= roomAbove
        // The fraction still applies on top of that: even with the room for
        // it, a menu that covers the screen stops reading as a menu.
        val maxCardHeight = minOf(
            maxOf(roomBelow, roomAbove),
            (window.height * MAX_HEIGHT_FRACTION).toInt()
        ).coerceAtLeast(context.dp(96))
        shadowRoom.measure(
            View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxCardHeight + pad * 2, View.MeasureSpec.AT_MOST)
        )
        val cardWidth = shadowRoom.measuredWidth - pad * 2
        val cardHeight = minOf(shadowRoom.measuredHeight - pad * 2, maxCardHeight)

        // Positions are the popup window's, and the window is the card plus
        // its shadow gutter on every side - so what is clamped to the screen is
        // the card, and the offsets handed to the window are the card's minus
        // the gutter. The gutter is allowed off-screen (isClippingEnabled),
        // which is what lets the card land exactly where it was asked to.
        popup.width = cardWidth + pad * 2
        popup.height = cardHeight + pad * 2

        val edge = context.dp(8)
        // The touch is the card's leading edge until that would hang it off
        // the screen, at which point it slides back in rather than being
        // clipped - so a tap on the value at the far end still opens beside it.
        val cardLeft = if (alignToAnchorEnd) location[0] + anchor.width - cardWidth else touchX.toInt()
        val clampedLeft = cardLeft.coerceIn(edge, maxOf(edge, window.width - cardWidth - edge))
        val cardTop =
            if (below) location[1] + anchor.height else location[1] - cardHeight
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, clampedLeft - pad, cardTop - pad)

        // Open on the current value rather than at the top: on a long list it
        // is the one thing the user came to look at.
        if (selected > 0) {
            card.post {
                val target = content.getChildAt(selected) ?: return@post
                card.scrollTo(0, maxOf(0, target.top - cardHeight / 2 + target.height / 2))
            }
        }
    }

    // An option: its label, then the tick in a 48dp slot 4dp in from the
    // menu's end - the settings rows' control column. The current one is
    // tinted with the accent and its label set in it.
    private fun row(
        context: Context,
        surface: ResolvedSurface,
        label: CharSequence,
        checked: Boolean,
        density: Float,
        onClick: () -> Unit
    ): View {
        fun dp(value: Int) = (value * density).toInt()
        val dark = androidx.core.graphics.ColorUtils.calculateLuminance(surface.surface or OPAQUE) < HALF
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_HEIGHT_DP)
            setPadding(dp(16), dp(4), dp(4), dp(4))
            isClickable = true
            if (checked) {
                setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                    surface.accent, if (dark) CHECKED_ALPHA_DARK else CHECKED_ALPHA_LIGHT
                ))
            }
            foreground = rippleFor(context)
            setOnClickListener { onClick() }

            addView(
                TextView(context).apply {
                    text = label
                    setTextColor(if (checked) surface.accent else surface.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    if (checked) {
                        typeface = android.graphics.Typeface.create(MEDIUM_FAMILY, android.graphics.Typeface.NORMAL)
                    }
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            // The slot stays on every row so the labels wrap alike.
            addView(
                ImageView(context).apply {
                    if (checked) {
                        setImageResource(R.drawable.ic_check)
                        imageTintList = android.content.res.ColorStateList.valueOf(surface.accent)
                    }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(14), 0, dp(14), 0)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(TICK_SLOT_DP), dp(20)).apply { marginStart = dp(8) }
            )
        }
    }

    private fun rippleFor(context: Context): android.graphics.drawable.Drawable? {
        val attrs = intArrayOf(android.R.attr.selectableItemBackground)
        val typed = context.obtainStyledAttributes(attrs)
        val drawable = typed.getDrawable(0)
        typed.recycle()
        return drawable
    }
}
