package com.supernova.launcher.ui

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.supernova.launcher.R
import com.supernova.launcher.databinding.ItemFeedAddBinding
import com.supernova.launcher.databinding.ItemFeedCalendarBinding
import com.supernova.launcher.databinding.ItemFeedContactsBinding
import com.supernova.launcher.databinding.ItemFeedCustomBinding
import com.supernova.launcher.databinding.ItemFeedMediaBinding
import com.supernova.launcher.feed.CalendarCardSource
import com.supernova.launcher.feed.ContactsCardSource
import com.supernova.launcher.feed.FeedCard
import com.supernova.launcher.feed.MediaCardSource

// Renders the feed pane's card list. One view type per card kind; the list is
// tiny (a handful of cards), so submits rebuild wholesale and the pane's
// itemAnimator is null - no diffing, no cross-fades, no churn.
class FeedAdapter(
    private val callbacks: Callbacks,
    // Custom cards render through the shared binder (feed, drawer and search
    // draw the same card); its callbacks carry the launch and menu routes.
    private val cardBinder: CardViewBinder
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class Callbacks(
        val onGrantCalendar: () -> Unit,
        val onGrantContacts: () -> Unit,
        val onOpenEvent: (Long) -> Unit,
        val onOpenContact: (ContactsCardSource.Starred, View) -> Unit,
        val onAddCard: () -> Unit
    )

    sealed class Row {
        data class Media(val state: MediaCardSource.MediaState) : Row()
        data class CalendarRow(val state: CalendarCardSource.CalendarState) : Row()
        data class Contacts(val state: ContactsCardSource.ContactsState) : Row()
        data class Custom(val card: FeedCard) : Row()
        object AddCard : Row()
    }

    private val rows = ArrayList<Row>()

    // Resolved style ints, applied at bind time (binds are rare: feed-open
    // and source emissions only).
    private var cardColor: Int = 0xF0202124.toInt()
    private var textColor: Int = Color.WHITE
    private var accentColor: Int = Color.WHITE
    // Add card sits on the pane's dim, not on a card.
    private var paneTextColor: Int = Color.WHITE

    fun setAppearance(card: Int, text: Int, accent: Int, paneText: Int) {
        cardColor = card
        textColor = text
        accentColor = accent
        paneTextColor = paneText
        cardBinder.setAppearance(card, text, accent)
    }

    @Suppress("NotifyDataSetChanged")
    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Media -> TYPE_MEDIA
        is Row.CalendarRow -> TYPE_CALENDAR
        is Row.Contacts -> TYPE_CONTACTS
        is Row.Custom -> TYPE_CUSTOM
        Row.AddCard -> TYPE_ADD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        density = parent.resources.displayMetrics.density
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_MEDIA -> MediaHolder(ItemFeedMediaBinding.inflate(inflater, parent, false))
            TYPE_CALENDAR -> CalendarHolder(ItemFeedCalendarBinding.inflate(inflater, parent, false))
            TYPE_CONTACTS -> ContactsHolder(ItemFeedContactsBinding.inflate(inflater, parent, false))
            TYPE_CUSTOM -> CustomHolder(ItemFeedCustomBinding.inflate(inflater, parent, false))
            else -> AddHolder(ItemFeedAddBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Media -> (holder as MediaHolder).bind(row.state)
            is Row.CalendarRow -> (holder as CalendarHolder).bind(row.state)
            is Row.Contacts -> (holder as ContactsHolder).bind(row.state)
            is Row.Custom -> (holder as CustomHolder).bind(row.card)
            Row.AddCard -> (holder as AddHolder).bind()
        }
    }

    private fun tintCard(view: View) {
        (view.background?.mutate() as? GradientDrawable)?.setColor(cardColor)
    }

    private inner class MediaHolder(private val binding: ItemFeedMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(state: MediaCardSource.MediaState) {
            tintCard(binding.root)
            binding.mediaTitle.setTextColor(textColor)
            binding.mediaArtist.setTextColor(textColor)
            binding.mediaTitle.text = state.title
            binding.mediaArtist.text = state.artist
            if (state.artwork != null) {
                binding.mediaArt.setImageBitmap(state.artwork)
                binding.mediaArt.visibility = View.VISIBLE
            } else {
                binding.mediaArt.setImageDrawable(null)
                binding.mediaArt.visibility = View.GONE
            }
            binding.mediaPlayPause.setImageResource(
                if (state.isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
            )
            val controls = state.controller.transportControls
            binding.mediaPlayPause.setOnClickListener {
                runCatching { if (state.isPlaying) controls.pause() else controls.play() }
            }
            binding.mediaNext.setOnClickListener { runCatching { controls.skipToNext() } }
            binding.mediaPrev.setOnClickListener { runCatching { controls.skipToPrevious() } }
        }
    }

    private inner class CalendarHolder(private val binding: ItemFeedCalendarBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(state: CalendarCardSource.CalendarState) {
            tintCard(binding.root)
            binding.calendarHeader.setTextColor(accentColor)
            binding.calendarEvents.removeAllViews()
            when (state) {
                CalendarCardSource.CalendarState.NeedsPermission -> {
                    binding.calendarPermissionText.visibility = View.VISIBLE
                    binding.calendarPermissionText.setTextColor(textColor)
                    binding.calendarGrantButton.visibility = View.VISIBLE
                    binding.calendarGrantButton.setOnClickListener { callbacks.onGrantCalendar() }
                }
                is CalendarCardSource.CalendarState.Events -> {
                    binding.calendarPermissionText.visibility = View.GONE
                    binding.calendarGrantButton.visibility = View.GONE
                    val context = binding.root.context
                    state.events.forEach { event ->
                        val row = TextView(context).apply {
                            text = buildString {
                                append(dayLabel(event.beginMs))
                                if (!event.allDay) {
                                    append("  ")
                                    append(
                                        DateUtils.formatDateTime(
                                            context, event.beginMs, DateUtils.FORMAT_SHOW_TIME
                                        )
                                    )
                                }
                                append("  ")
                                append(event.title)
                            }
                            setTextColor(textColor)
                            textSize = 14f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(0, dp(6), 0, dp(6))
                            setOnClickListener { callbacks.onOpenEvent(event.eventId) }
                        }
                        binding.calendarEvents.addView(row)
                    }
                }
            }
        }

        // Today / Tomorrow / weekday, like the spec's event labels.
        private fun dayLabel(timeMs: Long): String {
            val context = binding.root.context
            return when {
                DateUtils.isToday(timeMs) -> context.getString(R.string.today)
                DateUtils.isToday(timeMs - DateUtils.DAY_IN_MILLIS) ->
                    context.getString(R.string.tomorrow)
                else -> DateUtils.formatDateTime(
                    context, timeMs,
                    DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
                )
            }
        }
    }

    private inner class ContactsHolder(private val binding: ItemFeedContactsBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(state: ContactsCardSource.ContactsState) {
            tintCard(binding.root)
            binding.contactsHeader.setTextColor(accentColor)
            binding.contactsRow.removeAllViews()
            when (state) {
                ContactsCardSource.ContactsState.NeedsPermission -> {
                    binding.contactsPermissionText.visibility = View.VISIBLE
                    binding.contactsPermissionText.setTextColor(textColor)
                    binding.contactsGrantButton.visibility = View.VISIBLE
                    binding.contactsGrantButton.setOnClickListener { callbacks.onGrantContacts() }
                }
                is ContactsCardSource.ContactsState.Contacts -> {
                    binding.contactsPermissionText.visibility = View.GONE
                    binding.contactsGrantButton.visibility = View.GONE
                    val context = binding.root.context
                    val size = dp(48)
                    state.contacts.forEach { contact ->
                        val tile: View = if (contact.photo != null) {
                            ImageView(context).apply {
                                setImageBitmap(contact.photo)
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                outlineProvider = CircleOutline
                                clipToOutline = true
                            }
                        } else {
                            TextView(context).apply {
                                text = contact.displayName.take(1).uppercase()
                                gravity = Gravity.CENTER
                                textSize = 18f
                                setTextColor(textColor)
                                background = GradientDrawable().apply {
                                    shape = GradientDrawable.OVAL
                                    setColor((0x33 shl 24) or (accentColor and 0xFFFFFF))
                                }
                            }
                        }
                        tile.contentDescription = contact.displayName
                        tile.setOnClickListener { callbacks.onOpenContact(contact, it) }
                        binding.contactsRow.addView(
                            tile,
                            LinearLayout.LayoutParams(size, size).apply { rightMargin = dp(10) }
                        )
                    }
                }
            }
        }
    }

    private inner class CustomHolder(private val binding: ItemFeedCustomBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(card: FeedCard) = cardBinder.bind(binding, card)
    }

    private inner class AddHolder(private val binding: ItemFeedAddBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            // Same fill as the action pills, so the button reads as one of them.
            (binding.feedAddCard.background?.mutate() as? GradientDrawable)
                ?.setColor((0x26 shl 24) or (paneTextColor and 0xFFFFFF))
            binding.feedAddCard.setTextColor(paneTextColor)
            binding.feedAddCard.setOnClickListener { callbacks.onAddCard() }
        }
    }

    private object CircleOutline : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private var density = 1f

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private const val TYPE_MEDIA = 0
        private const val TYPE_CALENDAR = 1
        private const val TYPE_CONTACTS = 2
        private const val TYPE_CUSTOM = 3
        private const val TYPE_ADD = 4
    }
}
