package com.supernova.launcher.onboarding

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.supernova.launcher.R

// The phone's animations are off. From here on the launcher's own follow only
// its Disable animations switch, so the first run asks once which way that
// starts. `answer` gets true for off.
internal object AnimationsQuestion {

    fun ask(context: Context, answer: (off: Boolean) -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.onboarding_animations_title)
            .setMessage(R.string.onboarding_animations_message)
            .setPositiveButton(R.string.onboarding_animations_keep) { _, _ -> answer(false) }
            .setNegativeButton(R.string.onboarding_animations_off) { _, _ -> answer(true) }
            .setCancelable(false)
            .show()
    }
}
