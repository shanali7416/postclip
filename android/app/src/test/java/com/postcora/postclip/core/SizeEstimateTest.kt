package com.postcora.postclip.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeEstimateTest {
    @Test fun thirtySecondsOfElevenAndHalfHours() {
        val full = 4_160_000_000L          // 4.16 GB
        val duration = (11.5 * 3600).toLong() // 41,400 s
        val clip = SizeEstimate.clipBytes(full, duration, 30)
        assertEquals(3.0, clip / 1e6, 0.05)
        assertEquals("3.0 MB", SizeEstimate.formatBytes(clip))
        assertEquals("4.16 GB", SizeEstimate.formatBytes(full))
        val note = SizeEstimate.longVideoNote(clip, full)
        assertTrue(note, note.startsWith("Estimated clip download: ≈3.0 MB — only this range is fetched"))
        assertTrue(note, note.contains("4.16 GB"))
    }

    @Test fun bitrateFallback() {
        assertEquals(3_750_000L, SizeEstimate.clipBytes(0, 0, 30, 1_000_000))
        assertEquals(0L, SizeEstimate.clipBytes(1000, 100, 0))
    }
}
