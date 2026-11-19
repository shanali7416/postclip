package com.postcora.postclip.core

import java.util.Locale

/** Size maths for range downloads. Uses decimal units (1 MB = 1,000,000 bytes). */
object SizeEstimate {

    /**
     * Estimated bytes fetched for a clip: the clip's share of the full stream size.
     * When content length is unknown, falls back to bitrate (bits/s) x seconds.
     */
    fun clipBytes(fullBytes: Long, durationSeconds: Long, clipSeconds: Long, bitrateBps: Long = 0): Long {
        if (clipSeconds <= 0) return 0
        if (fullBytes > 0 && durationSeconds > 0) {
            return Math.round(fullBytes.toDouble() * clipSeconds / durationSeconds)
        }
        if (bitrateBps > 0) return bitrateBps / 8 * clipSeconds
        return 0
    }

    /**
     * Estimated bytes for a "Download full video": the full stream sizes (video + audio) when YouTube reports
     * them, else total bitrate (bits/s) x video duration. 0 when neither is known.
     */
    fun fullVideoBytes(fullBytes: Long, durationSeconds: Long, bitrateBps: Long = 0): Long {
        if (fullBytes > 0) return fullBytes
        if (bitrateBps > 0 && durationSeconds > 0) return bitrateBps / 8 * durationSeconds
        return 0
    }

    fun formatBytes(bytes: Long): String {
        val b = bytes.toDouble()
        return when {
            b >= 1e9 -> String.format(Locale.US, "%.2f GB", b / 1e9)
            b >= 1e6 -> String.format(Locale.US, "%.1f MB", b / 1e6)
            b >= 1e3 -> String.format(Locale.US, "%.0f KB", b / 1e3)
            else -> "${bytes} B"
        }
    }

    fun formatSpeed(bytesPerSecond: Double): String =
        if (bytesPerSecond >= 1e6) String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1e6)
        else String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1e3)

    /** The line shown for videos longer than one hour. */
    fun longVideoNote(clipBytes: Long, fullBytes: Long): String =
        "Estimated clip download: ≈${formatBytes(clipBytes)} — only this range is fetched" +
            (if (fullBytes > 0) " (full video: ${formatBytes(fullBytes)})" else "")
}
