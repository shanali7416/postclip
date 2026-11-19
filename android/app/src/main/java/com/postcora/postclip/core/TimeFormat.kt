package com.postcora.postclip.core

/** Result of parsing a user-entered time field. */
sealed class TimeParse {
    data class Ok(val seconds: Long) : TimeParse()
    data class Error(val message: String) : TimeParse()
}

/**
 * Parses and formats clip times. Accepts `h:mm:ss`, `m:ss` (shorthand) and plain seconds.
 * Minutes and seconds fields that follow a larger unit must be < 60.
 */
object TimeFormat {

    const val UNDER_60 = "Minutes and seconds must be under 60"

    /** Separators other keyboards may offer; all are treated as ':'. */
    val SEPARATORS = charArrayOf(':', '.', ',', ' ', '-')

    fun normalizeSeparators(text: String): String =
        text.map { if (it in SEPARATORS) ':' else it }.joinToString("")

    fun parse(input: String): TimeParse {
        val text = normalizeSeparators(input.trim())
        if (text.isEmpty()) return TimeParse.Error("Enter a time like 1646 (16:46) or 30 (0:30)")
        val parts = text.split(":")
        if (parts.size > 3) return TimeParse.Error("Use h:mm:ss or m:ss")
        if (parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 6 }) {
            return TimeParse.Error("Use digits only, like 1:05:10 or 0:30")
        }
        val nums = parts.map { it.toLong() }
        return when (nums.size) {
            1 -> TimeParse.Ok(nums[0])
            2 -> {
                val (m, s) = nums
                if (s >= 60) TimeParse.Error(UNDER_60)
                else TimeParse.Ok(m * 60 + s)
            }
            else -> {
                val (h, m, s) = nums
                when {
                    m >= 60 || s >= 60 -> TimeParse.Error(UNDER_60)
                    else -> TimeParse.Ok(h * 3600 + m * 60 + s)
                }
            }
        }
    }

    /** Convenience: seconds or null when invalid. */
    fun parseSeconds(input: String): Long? = (parse(input) as? TimeParse.Ok)?.seconds

    /** Formats as h:mm:ss when >= 1 hour, otherwise m:ss. */
    fun format(totalSeconds: Long): String {
        val t = if (totalSeconds < 0) 0 else totalSeconds
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/**
 * Digits-only, timer-style entry for the Start/End fields (numeric keyboards have no ':' key).
 * Digits fill from the right: seconds, then minutes, then hours.
 *   1646 -> 16:46, 11646 -> 1:16:46, 30 -> 0:30, 50510 -> 5:05:10, 175 -> 1:75 (invalid, shown as an error).
 * Text with separators (':' '.' ',' ' ' '-') from a paste or a keyboard that has them is also accepted.
 */
object TimeInput {
    const val MAX_DIGITS = 6

    /** Formats a digit string timer-style. Leading zeros are dropped; "" stays "", all-zero becomes "0:00". */
    fun formatDigits(digits: String): String {
        val raw = digits.filter(Char::isDigit)
        if (raw.isEmpty()) return ""
        val d = raw.trimStart('0').ifEmpty { "0" }
        return when {
            d.length <= 2 -> "0:" + d.padStart(2, '0')
            d.length <= 4 -> d.dropLast(2) + ":" + d.takeLast(2)
            else -> d.dropLast(4) + ":" + d.dropLast(2).takeLast(2) + ":" + d.takeLast(2)
        }
    }

    /**
     * Turns any typed or pasted text into the digit string it stands for.
     * Separator segments after the first are padded to two digits ("1.5.10" -> 10510);
     * empty segments (a trailing ':' just typed) are ignored. Other characters are dropped.
     */
    fun toDigits(text: String): String {
        val norm = TimeFormat.normalizeSeparators(text).filter { it.isDigit() || it == ':' }
        val segs = norm.split(":").filter { it.isNotEmpty() }
        if (segs.isEmpty()) return ""
        return segs.first() + segs.drop(1).joinToString("") { it.padStart(2, '0') }
    }

    /**
     * New field text after an edit. [old] is the current (formatted) text, [new] what the field reported.
     * Backspace (new is a prefix of old) just drops a digit; more than [MAX_DIGITS] significant digits is refused.
     */
    fun reformat(old: String, new: String): String {
        val deleting = new.length < old.length && old.startsWith(new)
        val digits = if (deleting) new.filter(Char::isDigit) else toDigits(new)
        val significant = digits.trimStart('0')
        if (significant.length > MAX_DIGITS) return old
        if (deleting && significant.isEmpty()) return ""
        return formatDigits(digits)
    }
}

object ClipValidation {
    const val MAX_CLIP_SECONDS = 3L * 3600

    /** Returns an error message or null when the range is valid. */
    fun validate(start: Long, end: Long, durationSeconds: Long): String? = when {
        start < 0 -> "Start must not be negative"
        end <= start -> "End must be after start"
        durationSeconds > 0 && end > durationSeconds -> "End must not exceed the video duration (${TimeFormat.format(durationSeconds)})"
        else -> null
    }

    /** End time for a preset; plain arithmetic so it crosses hour boundaries (59:50 + 30s = 1:00:20). */
    fun presetEnd(start: Long, presetSeconds: Long): Long = start + presetSeconds

    val PRESETS = listOf(15L, 30L, 60L, 90L)
}
