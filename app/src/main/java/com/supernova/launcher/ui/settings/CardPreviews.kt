package com.supernova.launcher.ui.settings

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.supernova.launcher.R
import com.supernova.launcher.data.LauncherPrefs
import com.supernova.launcher.databinding.ItemFeedCalendarBinding
import com.supernova.launcher.databinding.ItemFeedContactsBinding
import com.supernova.launcher.databinding.ItemFeedMediaBinding
import com.supernova.launcher.ui.textSizeOf
import com.supernova.launcher.ui.theme.Styles
import com.supernova.launcher.ui.theme.SurfaceId

// The launcher's own cards, as the Cards page lists them: each one
// rendered with placeholder content, under a name and a gear.
enum class BuiltInCard { MEDIA, CALENDAR, CONTACTS }

object CardPreviews {

    fun builtIn(kind: BuiltInCard, parent: ViewGroup, prefs: LauncherPrefs): View {
        val context = parent.context
        val look = Styles.resolve(context, prefs).of(SurfaceId.CARDS)
        val inflater = LayoutInflater.from(context)
        val density = context.resources.displayMetrics.density
        return when (kind) {
            BuiltInCard.MEDIA -> ItemFeedMediaBinding.inflate(inflater, parent, false).apply {
                tint(root, look.surface)
                mediaTitle.setTextColor(look.text)
                mediaArtist.setTextColor(look.text)
                mediaTitle.setText(R.string.card_sample_title)
                mediaArtist.setText(R.string.card_sample_artist)
                mediaArt.visibility = View.GONE
                mediaPlayPause.setImageResource(R.drawable.ic_media_play)
            }.root
            BuiltInCard.CALENDAR -> ItemFeedCalendarBinding.inflate(inflater, parent, false).apply {
                tint(root, look.surface)
                calendarHeader.setTextColor(look.accent)
                calendarPermissionText.visibility = View.GONE
                calendarGrantButton.visibility = View.GONE
                calendarEvents.removeAllViews()
                val pad = (6 * density).toInt()
                listOf(
                    "${context.getString(R.string.today)}  10:00  ${context.getString(R.string.card_sample_event)}",
                    "${context.getString(R.string.tomorrow)}  14:30  ${context.getString(R.string.card_sample_event_2)}"
                ).forEach { line ->
                    calendarEvents.addView(TextView(context).apply {
                        text = line
                        setTextColor(look.text)
                        textSize = 14f
                        maxLines = 1
                        setPadding(0, pad, 0, pad)
                    })
                }
            }.root
            BuiltInCard.CONTACTS -> ItemFeedContactsBinding.inflate(inflater, parent, false).apply {
                tint(root, look.surface)
                contactsHeader.setTextColor(look.accent)
                contactsPermissionText.visibility = View.GONE
                contactsGrantButton.visibility = View.GONE
                contactsRow.removeAllViews()
                val size = (48 * density).toInt()
                "ABC".forEach { initial ->
                    contactsRow.addView(
                        TextView(context).apply {
                            text = initial.toString()
                            gravity = Gravity.CENTER
                            textSizeOf(R.dimen.text_title)
                            setTextColor(look.text)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor((0x33 shl 24) or (look.accent and 0xFFFFFF))
                            }
                        },
                        LinearLayout.LayoutParams(size, size).apply { rightMargin = (10 * density).toInt() }
                    )
                }
            }.root
        }
    }

    private fun tint(view: View, color: Int) {
        (view.background?.mutate() as? GradientDrawable)?.setColor(color)
    }
}
