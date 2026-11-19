package com.postcora.postclip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Digits-only timer-style entry for the Start/End fields (numeric keyboards have no ':' key). */
class TimeInputTest {
    private fun secs(s: String) = (TimeFormat.parse(s) as TimeParse.Ok).seconds

    /** Types [digits] one key at a time into an empty field, like the phone keyboard does. */
    private fun type(digits: String, start: String = ""): String =
        digits.fold(start) { field, c -> TimeInput.reformat(field, field + c) }

    @Test fun formatsDigitsFromTheRight() {
        assertEquals("16:46", TimeInput.formatDigits("1646"))
        assertEquals("1:16:46", TimeInput.formatDigits("11646"))
        assertEquals("0:30", TimeInput.formatDigits("30"))
        assertEquals("0:05", TimeInput.formatDigits("5"))
        assertEquals("5:05:10", TimeInput.formatDigits("50510"))
        assertEquals("99:59:59", TimeInput.formatDigits("995959"))
        assertEquals("0:00", TimeInput.formatDigits("000"))
        assertEquals("", TimeInput.formatDigits(""))
    }

    @Test fun digitEntryParsesToSeconds() {
        assertEquals(1006L, secs(TimeInput.formatDigits("1646")))
        assertEquals(4606L, secs(TimeInput.formatDigits("11646")))
        assertEquals(30L, secs(TimeInput.formatDigits("30")))
        assertEquals(18310L, secs(TimeInput.formatDigits("50510")))
    }

    @Test fun typingKeyByKey() {
        assertEquals("16:46", type("1646"))
        assertEquals("1:16:46", type("11646"))
        assertEquals("0:30", type("30"))
        assertEquals("5:05:10", type("50510"))
        // typing over the default "0:00" works too (leading zeros drop out)
        assertEquals("16:46", type("1646", start = "0:00"))
    }

    @Test fun overSixtyIsInvalid() {
        val f = type("175")
        assertEquals("1:75", f)
        val r = TimeFormat.parse(f)
        assertTrue(r is TimeParse.Error)
        assertEquals("Minutes and seconds must be under 60", (r as TimeParse.Error).message)
        assertEquals(TimeFormat.UNDER_60, (TimeFormat.parse(type("17500")) as TimeParse.Error).message)
    }

    @Test fun backspaceDropsLastDigit() {
        assertEquals("1:64", TimeInput.reformat("16:46", "16:4"))
        assertEquals("0:16", TimeInput.reformat("1:64", "1:6"))
        assertEquals("0:01", TimeInput.reformat("0:16", "0:1"))
        assertEquals("", TimeInput.reformat("0:01", "0:0"))
        assertEquals("", TimeInput.reformat("0:00", "0:0"))
        assertEquals("", TimeInput.reformat("16:46", ""))
    }

    @Test fun maxSixDigits() {
        assertEquals("99:59:59", TimeInput.reformat("99:59:59", "99:59:591"))
    }

    @Test fun separatorsAreAccepted() {
        assertEquals("1:05:10", TimeInput.reformat("", "1.05.10"))
        assertEquals(3910L, secs(TimeInput.reformat("", "1.05.10")))
        assertEquals(3910L, secs("1.05.10"))
        assertEquals(3910L, secs(TimeInput.reformat("", "1:5:10")))
        assertEquals(3910L, secs(TimeInput.reformat("", "1 05 10")))
        assertEquals(3910L, secs(TimeInput.reformat("", "1,05,10")))
        assertEquals(3910L, secs(TimeInput.reformat("", "1-05-10")))
        assertEquals(3910L, secs(TimeInput.reformat("0:00", "1:05:10")))  // paste over existing text
        // a ':' typed on a keyboard that has one is ignored (timer fills from the right)
        assertEquals("0:16", TimeInput.reformat("0:16", "0:16:"))
        assertEquals("16:46", type("46", start = TimeInput.reformat("0:16", "0:16:")))
    }

    @Test fun presetsAndLoadedValuesRoundTrip() {
        for (s in listOf(0L, 15L, 30L, 60L, 90L, 3599L, 3600L, 3910L, 3940L, 19390L, 41400L)) {
            val shown = TimeFormat.format(s)
            assertEquals(shown, TimeInput.formatDigits(TimeInput.toDigits(shown)))
            assertEquals(s, secs(TimeInput.formatDigits(TimeInput.toDigits(shown))))
            // the field keeps the value unchanged when it is re-reported as-is
            assertEquals(shown, TimeInput.reformat(shown, shown))
        }
    }
}
