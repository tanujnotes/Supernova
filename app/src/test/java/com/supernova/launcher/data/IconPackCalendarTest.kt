package com.supernova.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

class IconPackCalendarTest {

    @Test
    fun calendarDrawableNameAppendsTheDay() {
        assertEquals("calendar_1", IconPackRepository.calendarDrawableName("calendar_", 1))
        assertEquals("calendar_31", IconPackRepository.calendarDrawableName("calendar_", 31))
        // Some packs use no separator.
        assertEquals("cal15", IconPackRepository.calendarDrawableName("cal", 15))
    }
}
