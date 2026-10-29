package com.supernova.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.supernova.launcher.R
import com.supernova.launcher.ui.theme.ResolvedSurface
import android.os.Build

// The optional extra rows an item popup can carry, configured per surface by
// the popupItemsHome / popupItemsDrawer prefs.
enum class PopupExtraRow {
    HIDE,
    UNINSTALL,
    PLAY_STORE,
    CLOSE_APP,
    RELAUNCH,
    SAVE_APK;

    companion object {
        fun fromName(name: String): PopupExtraRow? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

// Codec for the popupItems* prefs: "" = surface defaults (today's behavior),
// "NONE" = explicitly nothing, else comma-joined PopupExtraRow names. The
// NONE token exists because "" already means "defaults", so an all-unchecked
// selection needs its own spelling.
object PopupRows {
    const val NONE_TOKEN = "NONE"

    // HIDE only makes sense in the drawer (the workspace has Remove).
    val homeChoices = listOf(
        PopupExtraRow.PLAY_STORE, PopupExtraRow.CLOSE_APP,
        PopupExtraRow.RELAUNCH, PopupExtraRow.SAVE_APK
    )
    // UNINSTALL is drawer-only too: the workspace menu removes the item, it
    // never uninstalls the app.
    val drawerChoices = listOf(PopupExtraRow.HIDE, PopupExtraRow.UNINSTALL) + homeChoices

    // Defaults reproduce the pre-phase-6 menus: the drawer had a Hide row,
    // the workspace menu had no extras.
    val homeDefaults: Set<PopupExtraRow> = emptySet()
    // Uninstall was an unconditional row before it became configurable, so it
    // has to be in the defaults for the menu to look unchanged.
    val drawerDefaults: Set<PopupExtraRow> = setOf(PopupExtraRow.HIDE, PopupExtraRow.UNINSTALL)

    fun decode(stored: String, defaults: Set<PopupExtraRow>): Set<PopupExtraRow> = when {
        stored.isEmpty() -> defaults
        stored.equals(NONE_TOKEN, ignoreCase = true) -> emptySet()
        else -> stored.split(',').mapNotNull(PopupExtraRow::fromName).toSet()
    }

    fun encode(rows: Set<PopupExtraRow>): String =
        if (rows.isEmpty()) NONE_TOKEN
        else PopupExtraRow.entries.filter { it in rows }.joinToString(",") { it.name }
}

// The launcher's own item context menu: a PopupWindow styled by the resolved
// POPUP surface, with leading icons, an optional notification-preview block
// on top and an app-shortcut block below, both divider-separated.
//
// Contract parity with android.widget.PopupMenu (the drag interplay in
// LauncherActivity depends on it): rows run their action and dismiss, and the
// dismiss listener fires exactly once on any dismissal path.
// Long-press menu layout (popupStyle pref, C4). BLOCK is the classic single
// slab; PILLS floats every row as its own pill; ANDROID12 is the large-radius
// spaced look of the platform's 12-era menus.
enum class PopupMenuStyle {
    BLOCK,
    PILLS,
    ANDROID12;

    // "Visually group shortcuts" splits a group into tiles; pills are
    // one row each already, so there it has nothing to do.
    val canGroup: Boolean get() = this != PILLS

    companion object {
        fun fromName(name: String): PopupMenuStyle =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BLOCK
    }
}

class SupernovaPopupMenu(
    private val context: Context,
    surface: ResolvedSurface,
    private val animate: Boolean = true,
    // popupCardBg pref: app-shortcut rows render inside a rounded inner card
    // instead of behind a divider.
    private val cardBackground: Boolean = false,
    private val style: PopupMenuStyle = PopupMenuStyle.BLOCK,
    // "Visually group shortcuts": the shortcut and notification groups
    // leave the menu's slab for tile groups of their own (newTileGroup).
    groupShortcuts: Boolean = false
) {

    // popupActionIcons: addRow's rows go into rows of icons instead. Set
    // before any row is added.
    var actionIcons: Boolean = false

    private val surfaceColor = surface.surface
    private val grouped = groupShortcuts && style.canGroup

    private enum class Group { NOTIFICATION, MAIN, SHORTCUT }

    private val density = context.resources.displayMetrics.density
    private val textColor = surface.text
    private val subTextColor = (0xB3 shl 24) or (surface.text and 0xFFFFFF)
    private val dividerColor = (0x33 shl 24) or (surface.text and 0xFFFFFF)

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumWidth = context.dp(216)
        when (style) {
            // Pills carry the surface per row; the container stays bare.
            PopupMenuStyle.PILLS -> setPadding(0, context.dp(2), 0, context.dp(2))
            PopupMenuStyle.BLOCK, PopupMenuStyle.ANDROID12 -> {
                setPadding(0, context.dp(if (style == PopupMenuStyle.ANDROID12) 10 else 8), 0, context.dp(if (style == PopupMenuStyle.ANDROID12) 10 else 8))
                background = com.supernova.launcher.ui.theme.SurfaceShapes.background(
                    surface.surface,
                    surface.cornerRadiiDp?.let { dpArr -> FloatArray(4) { dpArr[it] * density } },
                    surface.cornerCuts,
                    context.dp(if (style == PopupMenuStyle.ANDROID12) 28 else 20).toFloat()
                )
                clipToOutline = true
            }
        }
    }

    // Menus stay short (<= ~12 rows) but notifications + shortcuts can push a
    // small screen past its height; the scroll container caps it.
    // BLOCK and ANDROID12 are one slab, so the lift belongs on the scroller
    // and the shadow follows the slab's rounded outline. PILLS has no slab -
    // each row is its own floating pill - so those rows carry their own lift
    // instead, and a shadow here would draw a rectangle behind the gaps.
    private val scroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        if (style != PopupMenuStyle.PILLS) {
            background = content.background
            content.background = null
            clipToOutline = true
            elevation = ELEVATION_DP * density
        }
        addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    // A transparent gutter for the shadow to fall into. The popup window is
    // sized to its content, so without this the window ends at the menu's edge
    // and clips the shadow away - which is what elevation on the window itself
    // has been doing all along.
    private val shadowRoom = android.widget.FrameLayout(context).apply {
        clipToPadding = false
        clipChildren = false
        setPadding(context.dp(SHADOW_PAD_DP), context.dp(SHADOW_PAD_DP), context.dp(SHADOW_PAD_DP), context.dp(SHADOW_PAD_DP))
        addView(
            scroller,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    // Shown non-focusable and non-touch-modal: item menus appear DURING the
    // long-press gesture, and a touch-modal window would take over the still
    // -down stream - killing the lazy drag start that move-to-drag
    // depends on. makeModal() upgrades the window once the finger lifts.
    private val popup = PopupWindow(
        shadowRoom,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        false
    ).apply {
        setBackgroundDrawable(ColorDrawable(0))
        isOutsideTouchable = true
        // Lets the window extend past the screen edge so the menu can sit
        // where it was asked to with its shadow gutter hanging off; otherwise
        // the system pushes an oversized window back inside and drags the menu
        // off its mark by the gutter's width.
        isClippingEnabled = false
        // FLAG_NOT_FOCUSABLE already implies not-touch-modal, so the
        // still-down gesture keeps streaming on Android 9 too; setTouchModal
        // (API 29+) just makes it explicit.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isTouchModal = false
        // 0 (not the -1 default) so disabling animations kills the system's
        // fallback popup transition too.
        animationStyle = if (animate) R.style.FolderPopupAnimation else 0
    }

    // Whether the menu has been handed back to the user - finger up, no drag -
    // and should now behave like any other menu. The host reads it to decide
    // whether a touch landing outside belongs to the menu.
    var isModal: Boolean = false
        private set

    // The finger is up and no drag happened: behave like a normal menu again,
    // so back dismisses and an outside tap only closes it.
    //
    // Touch-modality cannot be turned back on here, though it reads as if it
    // can: PopupWindow.update() recomputes the window flags but never touches
    // FLAG_NOT_TOUCH_MODAL, so the window carries the value it was shown with
    // for life, and setTouchModal(true) now would silently do nothing. The
    // window therefore keeps letting outside touches through to the launcher,
    // and the host swallows them on this window's behalf.
    //
    // Which is why the window also stops watching for them itself: two owners
    // dismissing on the same tap is a race, and the tap that lost would fall
    // through and launch whatever it landed on.
    fun makeModal() {
        isModal = true
        popup.isFocusable = true
        popup.isOutsideTouchable = false
        popup.update()
    }

    val isShowing: Boolean get() = popup.isShowing

    private var lastGroup: Group? = null
    private var rowCount = 0

    val hasRows: Boolean get() = rowCount > 0

    fun setOnDismissListener(listener: (() -> Unit)?) {
        popup.setOnDismissListener(listener?.let { PopupWindow.OnDismissListener(it) })
    }

    // One "Title — text" line; tapping fires onClick (the notification's
    // contentIntent) and dismisses.
    fun addNotification(title: String, text: String, onClick: () -> Unit) {
        beginGroup(Group.NOTIFICATION)
        val label = SpannableStringBuilder().apply {
            if (title.isNotBlank()) {
                append(title)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (title.isNotBlank() && text.isNotBlank()) append("  —  ")
            if (text.isNotBlank()) append(text)
        }
        addGroupRow(buildRow(label, iconView(R.drawable.ic_menu_notification, context.dp(18)), sub = true, onClick), content)
        rowCount++
    }

    fun addRow(label: CharSequence, @DrawableRes icon: Int, onClick: () -> Unit) {
        beginGroup(Group.MAIN)
        if (actionIcons) {
            addActionIcon(label, icon, onClick)
        } else {
            content.addView(buildRow(label, iconView(icon, context.dp(20)), sub = false, onClick))
        }
        rowCount++
    }

    // The icon row a menu's actions go into with popupActionIcons on. Past
    // ACTION_ICONS_PER_ROW they wrap onto another.
    private var actionStrip: LinearLayout? = null

    // An action as an icon alone: its name is its tooltip (held) and what a
    // screen reader says.
    private fun addActionIcon(label: CharSequence, @DrawableRes icon: Int, onClick: () -> Unit) {
        val strip = actionStrip?.takeIf { it.childCount < ACTION_ICONS_PER_ROW } ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
            if (style == PopupMenuStyle.PILLS) {
                background = GradientDrawable().apply {
                    setColor(surfaceColor)
                    cornerRadius = context.dp(24).toFloat()
                }
                elevation = ELEVATION_DP * density
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            content.addView(this)
            actionStrip = this
        }
        val button = ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(textColor)
            val pad = context.dp(ACTION_ICON_PAD_DP)
            setPadding(pad, pad, pad, pad)
            foreground = rippleDrawable()
            contentDescription = label
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, label)
            setOnClickListener {
                dismiss()
                onClick()
            }
            layoutParams = LinearLayout.LayoutParams(context.dp(ACTION_ICON_DP), context.dp(ACTION_ICON_DP))
        }
        strip.addView(button)
    }

    // App shortcuts render with their own badged icon, untinted. A held
    // shortcut can be dragged out onto the home screen: onLongPress arms that
    // with the row, and this menu's own window (window) carries the gesture.
    fun addShortcut(
        label: CharSequence,
        icon: Drawable?,
        onLongPress: ((View) -> Unit)? = null,
        onClick: () -> Unit
    ) {
        beginGroup(Group.SHORTCUT)
        val image = ImageView(context).apply {
            icon?.let(::setImageDrawable)
            layoutParams = LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply { marginEnd = context.dp(14) }
        }
        val row = buildRow(label, image, sub = false, onClick)
        if (onLongPress != null) {
            row.setOnLongClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongPress(view)
                true
            }
        }
        addGroupRow(row, shortcutCard ?: content)
        rowCount++
    }

    // The menu's window, for a drag taken out of it: its touches can be handed
    // to the drag engine (setTouchInterceptor), and the engine keeps it open,
    // hidden, until the drop.
    val window: PopupWindow get() = popup

    // The tile group rows are going into while a grouped shortcut or
    // notification group is open; null otherwise.
    private var tileGroup: LinearLayout? = null

    // The grouped look: each row a filled tile two dp from the next, and
    // the group's rounded outline clipping the outer corners of the first
    // and last. Inset like the shortcut card, and rounded to sit concentric
    // with the menu's corners.
    private fun newTileGroup(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0)
            cornerRadius = context.dp(if (style == PopupMenuStyle.ANDROID12) 20 else 12).toFloat()
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = context.dp(8)
            rightMargin = context.dp(8)
            topMargin = if (content.childCount > 0) context.dp(6) else 0
            bottomMargin = context.dp(2)
        }
        content.addView(this)
    }

    private fun addGroupRow(row: View, parent: LinearLayout) {
        val tiles = tileGroup
        if (tiles == null) {
            parent.addView(row)
            return
        }
        row.background = GradientDrawable().apply { setColor((0x1F shl 24) or (textColor and 0xFFFFFF)) }
        (row.layoutParams as LinearLayout.LayoutParams).topMargin = if (tiles.childCount > 0) context.dp(2) else 0
        tiles.addView(row)
    }

    // The shortcut group's card (cardBackground mode), created on the first
    // shortcut row: a slightly text-tinted rounded slab inset from the edges.
    private var shortcutCard: LinearLayout? = null

    private fun ensureShortcutCard(): LinearLayout = shortcutCard ?: LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor((0x14 shl 24) or (textColor and 0xFFFFFF))
            cornerRadius = context.dp(12).toFloat()
        }
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = context.dp(8)
            rightMargin = context.dp(8)
            topMargin = context.dp(6)
            bottomMargin = context.dp(2)
        }
        content.addView(this)
        shortcutCard = this
    }

    // touchX is where the finger went down, in window coordinates. An icon is
    // small enough that its own edge is the same answer, but a drawer row in
    // list style runs the full width of the screen, and a menu pinned to that
    // row's leading edge opens nowhere near the finger that opened it. Callers
    // with no touch to point at pass nothing and get the anchor's edge.
    fun show(anchor: View, touchX: Float? = null): SupernovaPopupMenu {
        // Before measuring: a menu long-pressed out of the drawer or the
        // search overlay opens with the keyboard up otherwise.
        anchor.hideKeyboardForWindow()

        // Placed absolutely rather than as a drop-down: showAsDropDown only
        // slides a too-tall popup, it doesn't flip it, so an anchor near the
        // screen bottom pushes rows under the nav bar - and its offsets mirror
        // themselves under a right-to-left layout, where one measured from a
        // touch must not.
        val window = anchor.rootView
        val pad = context.dp(SHADOW_PAD_DP)
        val root = popup.contentView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(window.height, View.MeasureSpec.AT_MOST)
        )
        // The measurement includes the shadow gutter; the menu people see is
        // what sits inside it.
        val cardWidth = root.measuredWidth - pad * 2
        val cardHeight = root.measuredHeight - pad * 2

        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        // Positions are the popup window's, and the window is the menu plus
        // its shadow gutter on every side - so what is clamped to the screen is
        // the menu, and the offsets handed to the window are the menu's minus
        // the gutter. The gutter is allowed off-screen (isClippingEnabled),
        // which is what lets the menu land exactly where it was asked to.
        popup.width = cardWidth + pad * 2
        popup.height = cardHeight + pad * 2

        val edge = context.dp(8)
        val cardLeft = (touchX?.toInt() ?: location[0])
            .coerceIn(edge, maxOf(edge, window.width - cardWidth - edge))
        val gap = context.dp(2)
        val spaceBelow = window.height - (location[1] + anchor.height)
        val cardTop =
            if (spaceBelow < cardHeight + gap) location[1] - cardHeight - gap
            else location[1] + anchor.height + gap
        // The application window's token, never the anchor's own: a folder
        // card is itself a panel window, and the window manager refuses a
        // panel whose parent is another panel (BadTokenException on a member
        // long-press). The card fills the window, so its coordinates are the
        // window's too.
        val appWindow = (context as? android.app.Activity)?.window?.decorView ?: anchor
        popup.showAtLocation(
            appWindow, Gravity.NO_GRAVITY, cardLeft - pad,
            cardTop.coerceAtLeast(edge) - pad
        )
        return this
    }

    fun dismiss() = popup.dismiss()

    private fun beginGroup(group: Group) {
        // Pills separate rows by their own margins; no dividers or cards.
        if (style == PopupMenuStyle.PILLS) {
            lastGroup = group
            return
        }
        // A tile group stands apart by its own margins: no divider or card.
        if (grouped && group != Group.MAIN) {
            if (lastGroup != group) tileGroup = newTileGroup()
            lastGroup = group
            return
        }
        val afterTiles = tileGroup != null
        tileGroup = null
        // Card mode replaces the shortcut group's divider with the card slab.
        if (cardBackground && group == Group.SHORTCUT) {
            if (lastGroup != group) ensureShortcutCard()
            lastGroup = group
            return
        }
        if (lastGroup != null && lastGroup != group && !afterTiles) {
            // Android 12 spaces groups instead of ruling them apart.
            if (style == PopupMenuStyle.ANDROID12) {
                content.addView(
                    View(context),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(6))
                )
            } else {
                content.addView(divider())
            }
        }
        lastGroup = group
    }

    private fun iconView(@DrawableRes icon: Int, sizePx: Int): ImageView =
        ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(textColor)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginEnd = context.dp(24) - (sizePx - context.dp(18)) / 2
            }
        }

    private fun buildRow(
        label: CharSequence,
        icon: ImageView,
        sub: Boolean,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val vPad = if (style == PopupMenuStyle.ANDROID12) 13 else 11
            setPadding(context.dp(18), context.dp(vPad), context.dp(18), context.dp(vPad))
            if (style == PopupMenuStyle.PILLS) {
                background = GradientDrawable().apply {
                    setColor(surfaceColor)
                    cornerRadius = context.dp(24).toFloat()
                }
                clipToOutline = true
                elevation = ELEVATION_DP * density
            }
            foreground = rippleDrawable()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (style == PopupMenuStyle.PILLS) {
                    topMargin = context.dp(2)
                    bottomMargin = context.dp(2)
                }
            }
            setOnClickListener {
                dismiss()
                onClick()
            }
        }
        row.addView(icon)
        row.addView(
            TextView(context).apply {
                text = label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = context.dp(220)
                setTextColor(if (sub) subTextColor else textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sub) 13f else 14f)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return row
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(dividerColor)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, context.dp(1)
        ).apply {
            topMargin = context.dp(6)
            bottomMargin = context.dp(6)
            marginStart = context.dp(14)
            marginEnd = context.dp(14)
        }
    }

    private fun rippleDrawable(): Drawable? {
        val value = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return if (value.resourceId != 0) context.getDrawable(value.resourceId) else null
    }


    companion object {
        // The menu's lift, and the room the window leaves around it for the
        // shadow that lift casts.
        // The gutter is generous against the lift on purpose: a material
        // shadow reaches about twice its elevation below the card, and a
        // gutter that merely looks roomy cuts the gradient off mid-fade,
        // leaving a hard line under the menu. Overshooting is free - the
        // gutter is transparent and may hang off the screen.
        private const val ELEVATION_DP = 8
        private const val SHADOW_PAD_DP = 22
        // The action icons: a 48dp touch target round a 20dp glyph, five a row.
        private const val ACTION_ICON_DP = 48
        private const val ACTION_ICON_PAD_DP = 14
        private const val ACTION_ICONS_PER_ROW = 5
    }
}
