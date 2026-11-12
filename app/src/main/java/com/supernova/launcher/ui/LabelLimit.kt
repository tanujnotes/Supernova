package com.supernova.launcher.ui

import android.text.InputFilter
import android.widget.EditText
import com.supernova.launcher.R

// The name fields built in code (drawer tab and folder dialogs) hold the
// same limit the layouts' rename fields set with maxLength.
object LabelLimit {
    fun apply(field: EditText) {
        val max = field.resources.getInteger(R.integer.label_max_length)
        field.filters = field.filters.filterNot { it is InputFilter.LengthFilter }.toTypedArray() +
            InputFilter.LengthFilter(max)
    }
}
