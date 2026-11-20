package com.supernova.launcher.ui

import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.supernova.launcher.data.Pref
import com.supernova.launcher.databinding.SheetFeedAddBinding

// Add to feed: a widget (straight to the picker, no editor), apps and
// shortcuts (the card editor), and the ready-made cards as switches. The
// switches write at once, like settings toggles; calendar and contacts ask
// for their access as they turn on.
object FeedAddSheet {

    fun show(
        activity: AppCompatActivity,
        viewModel: LauncherViewModel,
        // Null where no widget can be bound from here: the row hides.
        onWidget: (() -> Unit)?,
        onShortcuts: () -> Unit,
        requestCalendar: () -> Unit,
        requestContacts: () -> Unit
    ) {
        val binding = SheetFeedAddBinding.inflate(LayoutInflater.from(activity))
        val dialog = Sheets.open(activity)
        dialog.setContentView(binding.root)
        if (onWidget == null) {
            binding.addWidget.visibility = android.view.View.GONE
        } else {
            binding.addWidget.setOnClickListener {
                dialog.dismiss()
                onWidget()
            }
        }
        binding.addShortcuts.setOnClickListener {
            dialog.dismiss()
            onShortcuts()
        }
        val prefs = viewModel.state.value.prefs
        fun ready(row: android.view.View, switch: SwitchCompat, on: Boolean, set: (Boolean) -> Unit) {
            switch.isChecked = on
            switch.setOnCheckedChangeListener { _, checked -> set(checked) }
            row.setOnClickListener { switch.toggle() }
        }
        ready(binding.readyMedia, binding.readyMediaSwitch, prefs.feedShowMedia) {
            viewModel.set(Pref.feedShowMedia, it)
            if (it) {
                PermissionPrompts.explainNotificationAccess(
                    activity, com.supernova.launcher.R.string.notification_access_media
                )
            }
        }
        ready(binding.readyCalendar, binding.readyCalendarSwitch, prefs.feedShowCalendar) {
            viewModel.set(Pref.feedShowCalendar, it)
            if (it) requestCalendar()
        }
        ready(binding.readyContacts, binding.readyContactsSwitch, prefs.feedShowContacts) {
            viewModel.set(Pref.feedShowContacts, it)
            if (it) requestContacts()
        }
        dialog.show()
    }
}
