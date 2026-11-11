package com.supernova.launcher.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.supernova.launcher.R

// The app's bottom sheets. The plate, corners and navigation-bar color come
// from the theme's bottomSheetDialogTheme; this only fixes how a sheet opens.
object Sheets {

    // A sheet that opens fully. Material's default parks a tall sheet at a
    // "peek" height - about the top three quarters of the screen - and
    // leaves the rest, which is where the Save button lives, hanging below
    // the fold until the user thinks to drag. An editor should open open.
    // A drag down from the top still dismisses it.
    fun open(context: Context): BottomSheetDialog =
        BottomSheetDialog(context).apply {
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            OpenDialogs.track(this)
        }
}

// The dialogs and sheets that may be standing over an activity. The launcher
// takes its own down on a Home press, as it does its menus and the drawer: an
// Edit sheet left up had the press run the home button's action behind it,
// and the drawer opened under the sheet.
//
// Held weakly and only asked while showing, so nothing has to be taken out.
// Main thread only.
object OpenDialogs {

    private val tracked = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Dialog, Boolean>())

    fun track(dialog: Dialog) {
        tracked += dialog
    }

    // Dismisses every one opened from this activity; whether any was up.
    fun dismissAll(owner: Activity): Boolean {
        val open = tracked.filter { it.isShowing && it.context.owningActivity() === owner }
        open.forEach { it.dismiss() }
        return open.isNotEmpty()
    }

    private fun Context.owningActivity(): Activity? {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }
}

// show(), for a dialog the launcher may open: a Home press closes it.
fun MaterialAlertDialogBuilder.showTracked(): AlertDialog = show().also(OpenDialogs::track)

// A text field for a dialog. A bare EditText handed to setView lands flush
// against the dialog's edges with an underline and nothing else; this wraps
// it in the theme's outlined field (the same one the sheets use) and gives it
// the dialog's own 24dp gutter, so a rename dialog reads like the rest of the
// app rather than like a form from 2014.
object DialogField {

    fun wrap(input: EditText): View {
        val context = input.context
        val density = context.resources.displayMetrics.density
        // The field's own hint becomes the floating label; without this the
        // hint would draw twice, once in the box and once on its border.
        val hint = input.hint
        input.hint = null
        // An EditText that arrives with the platform underline keeps it:
        // TextInputLayout only draws its box over a field with no background
        // of its own.
        input.background = null
        // Names of apps, folders, groups and cards: nothing an autofill
        // service has an answer for.
        input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        // Configured here rather than left to the theme's textInputStyle: an
        // EditText built in code arrives wrapped in Material's own edit-text
        // overlay, and a TextInputLayout created from that context does not
        // pick the app's outlined style up.
        val field = TextInputLayout(context).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            val radius = context.dp(14).toFloat()
            setBoxCornerRadii(radius, radius, radius, radius)
            setBoxStrokeColorStateList(
                AppCompatResources.getColorStateList(context, R.color.text_input_stroke)
            )
            val accent = context.themeColor(android.R.attr.colorAccent)
            hintTextColor = ColorStateList.valueOf(accent)
            defaultHintTextColor = ColorStateList.valueOf(context.getColor(R.color.textSecondary))
            // TextInputLayout is a LinearLayout and casts the child's params
            // to its own; generic ViewGroup params make addView throw.
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return FrameLayout(context).apply {
            setPadding(context.dp(24), context.dp(8), context.dp(24), 0)
            addView(
                field,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }
}

private fun Context.themeColor(attr: Int): Int {
    val typed = obtainStyledAttributes(intArrayOf(attr))
    val color = typed.getColor(0, 0)
    typed.recycle()
    return color
}
