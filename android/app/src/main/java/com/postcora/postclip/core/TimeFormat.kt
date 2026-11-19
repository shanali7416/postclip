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

    fun parse(input: String): TimeParse {
        val text = input.trim()
        if (text.isEmpty()) return TimeParse.Error("Enter a time like 1:05:10 or 0:30")
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
                if (s >= 60) TimeParse.Error("Seconds must be less than 60")
                else TimeParse.Ok(m * 60 + s)
            }
            else -> {
                val (h, m, s) = nums
                when {
                    m >= 60 -> TimeParse.Error("Minutes must be less than 60")
                    s >= 60 -> TimeParse.Error("Seconds must be less than 60")
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
