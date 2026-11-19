package com.postcora.postclip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatTest {
    private fun secs(s: String) = (TimeFormat.parse(s) as TimeParse.Ok).seconds

    @Test fun parsesHourFormat() {
        assertEquals(3910L, secs("1:05:10"))
        assertEquals(3940L, secs("1:05:40"))
        assertEquals(19390L, secs("5:23:10"))
    }

    @Test fun parsesShorthand() {
        assertEquals(30L, secs("0:30"))
        assertEquals(83L, secs("1:23"))
        assertEquals(45L, secs("45"))
        assertEquals(30L, secs(" 0:30 "))
    }

    @Test fun rejectsInvalid() {
        for (bad in listOf("", "abc", "1:60", "1:61:00", "1:05:60", "1::10", "1:2:3:4", "-5", "1:0a", "1.5")) {
            assertTrue("expected error for '$bad'", TimeFormat.parse(bad) is TimeParse.Error)
        }
        assertEquals("Seconds must be less than 60", (TimeFormat.parse("0:75") as TimeParse.Error).message)
        assertEquals("Minutes must be less than 60", (TimeFormat.parse("1:75:00") as TimeParse.Error).message)
    }

    @Test fun formatsRoundTrip() {
        assertEquals("1:05:10", TimeFormat.format(3910))
        assertEquals("0:30", TimeFormat.format(30))
        assertEquals("5:23:10", TimeFormat.format(19390))
        for (s in listOf(0L, 59L, 3599L, 3600L, 3910L, 19390L, 41400L)) assertEquals(s, secs(TimeFormat.format(s)))
    }

    @Test fun validation() {
        assertNull(ClipValidation.validate(3910, 3940, 41400))
        assertEquals("End must be after start", ClipValidation.validate(30, 30, 100))
        assertEquals("End must be after start", ClipValidation.validate(40, 30, 100))
        assertNotNull(ClipValidation.validate(60, 120, 100))
        assertTrue(ClipValidation.validate(60, 120, 100)!!.startsWith("End must not exceed"))
    }

    @Test fun presetsCrossHourBoundary() {
        val start = secs("59:50")
        assertEquals("1:00:20", TimeFormat.format(ClipValidation.presetEnd(start, 30)))
        assertEquals("2:01:29", TimeFormat.format(ClipValidation.presetEnd(secs("1:59:59"), 90)))
        assertEquals(3940L, ClipValidation.presetEnd(3910, 30))
    }
}
