// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetronomeTest {

    @Test
    fun framesPerBeatAndCountIn() {
        val m = MetronomeSettings(bpm = 120.0, beatsPerBar = 4, countInBars = 2)
        assertEquals(24_000.0, m.framesPerBeat(48_000), 1e-9)
        assertEquals(192_000L, m.countInFrames(48_000))
    }

    @Test
    fun beatIndexIsNegativeDuringCountIn() {
        val m = MetronomeSettings(bpm = 120.0)
        assertEquals(-1L, m.beatAt(-1, 48_000))
        assertEquals(-4L, m.beatAt(-96_000, 48_000))
        assertEquals(0L, m.beatAt(0, 48_000))
        assertEquals(1L, m.beatAt(24_000, 48_000))
    }

    @Test
    fun tapTempoAveragesRecentIntervals() {
        assertNull(MetronomeSettings.bpmFromTaps(listOf(1_000)))
        assertEquals(120.0, MetronomeSettings.bpmFromTaps(listOf(0, 500, 1_000, 1_500))!!, 1e-9)
        // Only the last 4 intervals count: an early slow tap is ignored.
        assertEquals(100.0, MetronomeSettings.bpmFromTaps(listOf(0, 2_000, 2_600, 3_200, 3_800, 4_400))!!, 1e-9)
    }

    @Test
    fun normalizationClampsRanges() {
        val n = MetronomeSettings(bpm = 500.0, countInBars = 9, beatsPerBar = 0, volume = 2f).normalized()
        assertEquals(300.0, n.bpm, 0.0)
        assertEquals(4, n.countInBars)
        assertEquals(1, n.beatsPerBar)
        assertEquals(1f, n.volume, 0f)
    }

    @Test
    fun dbConversion() {
        assertEquals(1.0, Levels.dbToLinear(0.0), 1e-12)
        assertEquals(0.0, Levels.dbToLinear(Levels.MIN_DB), 0.0)
        assertEquals(2.0, Levels.dbToLinear(6.0206), 1e-4)
    }
}
