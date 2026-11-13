package com.supernova.launcher.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.provider.CalendarContract
import android.speech.RecognizerIntent
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.ImageViewCompat
import com.supernova.launcher.R
import com.supernova.launcher.SettingsActivity
import com.supernova.launcher.runLogged

// The buttons at a search bar's right end. The home bar and the drawer field
// offer the same ones and draw them the same way; each keeps its own list
// (the home bar's config chips, the drawer's drawerActionButtons pref), in
// the order the buttons were turned on.
object SearchBarButtons {

    // How both bars' settings pages list them.
    val ROWS = listOf(
        SearchBarChip.DATE, SearchBarChip.ASSISTANT, SearchBarChip.PLAY_STORE,
        SearchBarChip.VOICE, SearchBarChip.SETTINGS
    )

    // Sized like the magnifier on the left: a 36dp box with 8dp of padding
    // leaves its 20dp.
    const val BUTTON_DP = 36
    private const val BUTTON_PAD_DP = 8

    @StringRes
    fun label(chip: SearchBarChip): Int = when (chip) {
        SearchBarChip.VOICE -> R.string.search_bar_chip_voice
        SearchBarChip.ASSISTANT -> R.string.search_bar_chip_assistant
        SearchBarChip.DATE -> R.string.search_bar_chip_date
        SearchBarChip.PLAY_STORE -> R.string.chip_play_store
        SearchBarChip.SETTINGS -> R.string.settings
    }

    // The date is text, so it has no icon.
    @DrawableRes
    private fun icon(chip: SearchBarChip): Int? = when (chip) {
        SearchBarChip.VOICE -> R.drawable.ic_mic
        SearchBarChip.ASSISTANT -> R.drawable.ic_assistant
        SearchBarChip.DATE -> null
        SearchBarChip.PLAY_STORE -> R.drawable.ic_play_store
        SearchBarChip.SETTINGS -> R.drawable.ic_tab_settings
    }

    // The drawer's stored list ("PLAY_STORE,SETTINGS"); unknown names drop.
    fun decode(raw: String): List<SearchBarChip> =
        raw.split(',').mapNotNull { SearchBarChip.fromName(it.trim()) }.distinct()

    fun encode(chips: List<SearchBarChip>): String = chips.joinToString(",") { it.name }

    // A list with one button turned on (added at the end) or off.
    fun toggled(chips: List<SearchBarChip>, chip: SearchBarChip, on: Boolean): List<SearchBarChip> {
        val without = chips.filterNot { it == chip }
        return if (on) without + chip else without
    }

    // A button for a bar: an icon in [color], or the date as text in it.
    // With no onClick it is a picture only (the settings previews).
    fun view(context: Context, chip: SearchBarChip, color: Int, onClick: (() -> Unit)?): View {
        val pad = context.dp(BUTTON_PAD_DP)
        val iconRes = icon(chip)
        val view = if (iconRes == null) {
            TextView(context).apply {
                textSize = DATE_SP
                maxLines = 1
                gravity = Gravity.CENTER
                setPadding(context.dp(DATE_PAD_DP), pad, context.dp(DATE_PAD_DP), pad)
                setTextColor(color)
                text = dateText(context)
            }
        } else {
            ImageView(context).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color))
            }
        }
        if (onClick == null) {
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            view.contentDescription = context.getString(label(chip))
            view.background = borderlessRipple(context)
            view.setOnClickListener { onClick() }
        }
        return view
    }

    fun dateText(context: Context): String = DateUtils.formatDateTime(
        context, System.currentTimeMillis(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
    )

    // What a tap does. Each is blind-started: a missing handler just no-ops.
    fun run(context: Context, chip: SearchBarChip) {
        when (chip) {
            SearchBarChip.VOICE -> runLogged("start voice search") {
                context.startActivity(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                )
            }
            // Same ladder as the ASSISTANT gesture action, with the
            // voice-command front door first.
            SearchBarChip.ASSISTANT -> {
                if (runCatching { context.startActivity(Intent(Intent.ACTION_VOICE_COMMAND)) }.isFailure) {
                    runLogged("open assistant") { context.startActivity(Intent(Intent.ACTION_ASSIST)) }
                }
            }
            // The documented "open calendar at a day" deep link.
            SearchBarChip.DATE -> {
                val uri = CalendarContract.CONTENT_URI.buildUpon()
                    .appendPath("time")
                    .also { ContentUris.appendId(it, System.currentTimeMillis()) }
                    .build()
                runLogged("open link") { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }
            SearchBarChip.PLAY_STORE -> runLogged("open Play Store") {
                context.packageManager.getLaunchIntentForPackage("com.android.vending")
                    ?.let(context::startActivity)
            }
            SearchBarChip.SETTINGS -> context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }

    private fun borderlessRipple(context: Context) =
        TypedValue().takeIf {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }?.resourceId?.takeIf { it != 0 }?.let { AppCompatResources.getDrawable(context, it) }

    private const val DATE_SP = 13f
    private const val DATE_PAD_DP = 6
}
