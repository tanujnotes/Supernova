package com.supernova.launcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.supernova.launcher.R
import com.supernova.launcher.databinding.ItemFeedCustomBinding
import com.supernova.launcher.feed.FeaturedType
import com.supernova.launcher.feed.FeedCard

// Renders a custom card into item_feed_custom: type mark + name +
// menu, a large featured tile (or hosted widget), a hairline, then wrapping
// icon+label action pills. One binder per surface (feed, drawer, search,
// editor preview) so each can route launches and the menu its own way; the
// look is identical everywhere.
class CardViewBinder(
    private val callbacks: Callbacks,
    // Whether a long-press on the card opens its menu. The feed says no:
    // there a long-press picks the card up to move it, and the menu opens
    // when it is let go where it was (FeedCardOrder).
    private val longPressMenu: Boolean = true
) {

    class Callbacks(
        // type APP/SHORTCUT; target uses the gesture payload encodings.
        val onLaunchTarget: (FeaturedType, String, View) -> Unit,
        // Hosted widget view for a WIDGET featured card, or null when the id
        // is unbound (post-restore) - the card then shows nothing featured.
        val widgetView: (Int) -> View?,
        val iconForTarget: (String) -> Drawable?,
        // App / shortcut label for a target, used for the featured tile and
        // as the header fallback for an unnamed card.
        val labelForTarget: (String) -> String?,
        // Null hides the menu button and the long-press (editor preview).
        val onMenu: ((FeedCard, View) -> Unit)?,
        // The history mark beside the menu: a tap reports the card's last
        // activation. Null hides it.
        val onHistory: ((FeedCard, View) -> Unit)? = null
    )

    private var cardColor: Int = 0xF0202124.toInt()
    private var textColor: Int = Color.WHITE
    private var accentColor: Int = Color.WHITE

    fun setAppearance(card: Int, text: Int, accent: Int) {
        cardColor = card
        textColor = text
        accentColor = accent
    }

    fun inflate(parent: ViewGroup): ItemFeedCustomBinding =
        ItemFeedCustomBinding.inflate(LayoutInflater.from(parent.context), parent, false)

    fun bind(binding: ItemFeedCustomBinding, card: FeedCard) {
        val context = binding.root.context
        bindFrame(binding, card.showFrame)
        binding.cardTypeIcon.imageTintList = ColorStateList.valueOf(textColor)
        binding.customName.setTextColor(textColor)
        val featuredLabel = card.featuredTarget?.let { target ->
            if (FeaturedType.fromName(card.featuredType) == FeaturedType.WIDGET) null
            else callbacks.labelForTarget(target)
        }
        binding.customName.text =
            card.name.ifBlank { featuredLabel ?: context.getString(R.string.card_untitled) }

        val history = callbacks.onHistory
        binding.cardHistory.visibility = if (history == null) View.GONE else View.VISIBLE
        binding.cardHistory.imageTintList = ColorStateList.valueOf(textColor)
        binding.cardHistory.setOnClickListener { anchor -> history?.invoke(card, anchor) }
        val menu = callbacks.onMenu
        binding.cardMenu.visibility = if (menu == null) View.GONE else View.VISIBLE
        binding.cardMenu.imageTintList = ColorStateList.valueOf(textColor)
        binding.cardMenu.setOnClickListener { anchor -> menu?.invoke(card, anchor) }

        val hasFeatured = bindFeatured(binding, card, featuredLabel)
        bindLongPress(binding, card)
        val hasActions = bindActions(binding, card)
        binding.cardDivider.setBackgroundColor((0x33 shl 24) or (textColor and 0xFFFFFF))
        binding.cardDivider.visibility = if (hasFeatured && hasActions) View.VISIBLE else View.GONE
        binding.customActions.visibility = if (hasActions) View.VISIBLE else View.GONE
        (binding.customActions.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin =
            context.dp(if (hasFeatured) 10 else 2)
    }

    // The card's menu as its ⋮ opens it, or against the card when it is
    // drawn bare. False when this surface has no menu.
    fun openMenu(binding: ItemFeedCustomBinding, card: FeedCard): Boolean {
        val menu = callbacks.onMenu ?: return false
        menu(card, if (card.showFrame) binding.cardMenu else binding.root)
        return true
    }

    // A long-press opens the menu where this surface wants it to. A hosted
    // widget performs the long-click itself, not the card, so it gets the
    // card's too; one widget view serves the feed and search in turn, so it
    // is set (or cleared) on every bind.
    private fun bindLongPress(binding: ItemFeedCustomBinding, card: FeedCard) {
        val longPress = if (longPressMenu && callbacks.onMenu != null) {
            View.OnLongClickListener { openMenu(binding, card) }
        } else {
            null
        }
        val hosted = binding.customFeatured.getChildAt(0) as? android.appwidget.AppWidgetHostView
        for (view in listOfNotNull(binding.root, hosted)) {
            view.setOnLongClickListener(longPress)
            if (longPress == null) view.isLongClickable = false
        }
    }

    // The plate and the header row, or neither: a card with its background
    // off is its content alone, edge to edge, like a widget on the home
    // screen. Set both ways on every bind, since rows are reused.
    private fun bindFrame(binding: ItemFeedCustomBinding, framed: Boolean) {
        val context = binding.root.context
        (binding.root.background?.mutate() as? GradientDrawable)
            ?.setColor(if (framed) cardColor else Color.TRANSPARENT)
        binding.cardHeader.visibility = if (framed) View.VISIBLE else View.GONE
        val side = if (framed) context.resources.getDimensionPixelSize(R.dimen.space_md) else 0
        val edge = if (framed) context.resources.getDimensionPixelSize(R.dimen.space_ms) else 0
        binding.root.setPadding(side, edge, side, edge)
        (binding.customFeatured.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin =
            if (framed) context.resources.getDimensionPixelSize(R.dimen.space_sm) else 0
    }

    private fun bindFeatured(
        binding: ItemFeedCustomBinding,
        card: FeedCard,
        featuredLabel: String?
    ): Boolean {
        val container = binding.customFeatured
        container.removeAllViews()
        val context = container.context
        val type = FeaturedType.fromName(card.featuredType)
        val shown: Boolean = when (type) {
            FeaturedType.APP, FeaturedType.SHORTCUT -> {
                val target = card.featuredTarget
                if (target == null) {
                    false
                } else {
                    val tile = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        foreground = context.getDrawable(R.drawable.ripple_icon)
                        setPadding(0, context.dp(6), 0, context.dp(6))
                        setOnClickListener { callbacks.onLaunchTarget(type, target, it) }
                        contentDescription = featuredLabel ?: card.name
                    }
                    val icon = ImageView(context).apply {
                        setImageDrawable(copied(callbacks.iconForTarget(target)))
                    }
                    val size = context.dp(56)
                    tile.addView(icon, LinearLayout.LayoutParams(size, size))
                    val label = FadingTextView(context).apply {
                        text = featuredLabel
                            ?: com.supernova.launcher.data.LaunchTarget.split(target).first.substringBefore('/')
                        setTextColor(textColor)
                        textSizeOf(R.dimen.text_headline)
                        maxLines = 1
                        setPadding(context.dp(18), 0, 0, 0)
                    }
                    tile.addView(
                        label,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    container.addView(
                        tile,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    true
                }
            }
            FeaturedType.WIDGET -> {
                val hosted = card.appWidgetId?.let(callbacks.widgetView)
                if (hosted == null) {
                    // The stand-in where the widget can't be hosted: the
                    // settings rendering, or an id left unbound by a restore.
                    container.addView(
                        TextView(context).apply {
                            setText(R.string.card_widget_unavailable)
                            setTextColor((0xB3 shl 24) or (textColor and 0xFFFFFF))
                            textSizeOf(R.dimen.text_body)
                            setPadding(0, context.dp(14), 0, context.dp(14))
                        },
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    true
                } else {
                    (hosted.parent as? ViewGroup)?.removeView(hosted)
                    container.addView(
                        hosted,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, context.dp(card.widgetHeightDp)
                        )
                    )
                    true
                }
            }
            null -> false
        }
        container.visibility = if (shown) View.VISIBLE else View.GONE
        return shown
    }

    private fun bindActions(binding: ItemFeedCustomBinding, card: FeedCard): Boolean {
        val flow = binding.customActions
        flow.removeAllViews()
        val context = flow.context
        var count = 0
        card.actions.take(FeedCard.MAX_ACTIONS).forEach { action ->
            val type = FeaturedType.fromName(action.type) ?: return@forEach
            if (type == FeaturedType.WIDGET) return@forEach
            val label = action.label.ifBlank {
                callbacks.labelForTarget(action.target)
                    ?: com.supernova.launcher.data.LaunchTarget.split(action.target).first.substringBefore('/')
            }
            flow.addView(
                pill(context, callbacks.iconForTarget(action.target), label, textColor) { view ->
                    callbacks.onLaunchTarget(type, action.target, view)
                }
            )
            count++
        }
        return count > 0
    }

    companion object {
        // An icon + label pill; also the feed's suggested-shortcut chip, so
        // both read as one family. The fill is a tint of the text color and
        // the icon is a per-view copy (cached drawables share bounds).
        fun pill(
            context: Context,
            icon: Drawable?,
            label: CharSequence,
            textColor: Int,
            // Search's shortcut pills: menu and drag. Cards leave it off.
            onLongPress: ((View) -> Unit)? = null,
            onTap: (View) -> Unit
        ): View {
            val pill = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = (context.getDrawable(R.drawable.bg_card_chip)?.mutate() as? GradientDrawable)
                    ?.apply { setColor((0x26 shl 24) or (textColor and 0xFFFFFF)) }
                foreground = context.getDrawable(R.drawable.ripple_icon)
                val pad = context.dp(6)
                setPadding(if (icon != null) context.dp(8) else context.dp(14), pad, context.dp(14), pad)
                contentDescription = label
                setOnClickListener { onTap(it) }
                onLongPress?.let { longPress ->
                    setOnLongClickListener {
                        longPress(it)
                        true
                    }
                }
            }
            if (icon != null) {
                val iconView = ImageView(context).apply { setImageDrawable(copied(icon)) }
                val size = context.dp(22)
                pill.addView(iconView, LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = context.dp(8)
                })
            }
            pill.addView(
                FadingTextView(context).apply {
                    text = label
                    setTextColor(textColor)
                    textSize = 14f
                    maxLines = 1
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return pill
        }

        private fun copied(icon: Drawable?): Drawable? =
            icon?.constantState?.newDrawable()?.mutate() ?: icon

    }
}
