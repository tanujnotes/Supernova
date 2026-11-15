package com.supernova.launcher.ui.theme

// A daily window in minutes since midnight, [start, end), as the theme
// schedule keeps it: an end before the start wraps past midnight, and a
// start equal to the end is the whole day.
object DayWindow {
    fun contains(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }
}
