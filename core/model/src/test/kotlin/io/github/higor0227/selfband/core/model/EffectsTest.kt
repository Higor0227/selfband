// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {
    private val fs = 48_000

    @Test
    fun neutralEqIsFlat() {
        val eq = TrackEq()
        assertTrue(eq.isNeutral)
        for (hz in EqMath.curveFrequencies(64)) {
            assertEquals("at $hz Hz", 0.0, EqMath.responseDb(eq, hz, fs), 1e-6)
        }
    }

    @Test
    fun peakBandHitsItsGainAtItsFrequency() {
        val eq = TrackEq(peakHz = 1000.0, peakDb = 6.0, peakQ = 1.0)
        assertEquals(6.0, EqMath.responseDb(eq, 1000.0, fs), 0.01)
        assertEquals(0.0, EqMath.responseDb(eq, 20.0, fs), 0.1)
        assertEquals(0.0, EqMath.responseDb(eq, 18_000.0, fs), 0.1)
        val cut = eq.copy(peakDb = -9.0)
        assertEquals(-9.0, EqMath.responseDb(cut, 1000.0, fs), 0.01)
    }

    @Test
    fun highPassIsMinus3dBAtCutoffAnd12dBPerOctaveBelow() {
        val eq = TrackEq(highPass = true, highPassHz = 100.0)
        assertEquals(-3.01, EqMath.responseDb(eq, 100.0, fs), 0.05)
        assertEquals(0.0, EqMath.responseDb(eq, 2000.0, fs), 0.05)
        // Two octaves below: about -24 dB (second order).
        assertEquals(-24.0, EqMath.responseDb(eq, 25.0, fs), 1.0)
    }

    @Test
    fun shelvesLiftTheirSideOnly() {
        val low = TrackEq(lowShelfHz = 120.0, lowShelfDb = 6.0)
        assertEquals(6.0, EqMath.responseDb(low, 20.0, fs), 0.2)
        assertEquals(3.0, EqMath.responseDb(low, 120.0, fs), 0.05) // half the gain at the corner
        assertEquals(0.0, EqMath.responseDb(low, 10_000.0, fs), 0.05)
        val high = TrackEq(highShelfHz = 8000.0, highShelfDb = -6.0)
        assertEquals(-6.0, EqMath.responseDb(high, 20_000.0, fs), 0.5)
        assertEquals(0.0, EqMath.responseDb(high, 100.0, fs), 0.05)
    }

    @Test
    fun bypassKeepsSettingsButIsFlat() {
        val eq = TrackEq(enabled = false, highPass = true, peakDb = 12.0)
        assertTrue(eq.isNeutral)
        assertEquals(0.0, EqMath.responseDb(eq, 1000.0, fs), 0.0)
        assertEquals(0f, EqMath.nativeParams(eq)[0], 0f)
        assertEquals(12f, EqMath.nativeParams(eq)[6], 0f)
    }

    @Test
    fun phaseInvertNegatesTheGainsAndSendIsOffByDefault() {
        val t = Track(id = "a", name = "a", phaseInverted = true, pan = 0.5f)
        val g = Mixing.trackGain(t, anySolo = false)
        assertEquals(-0.5f, g.left, 1e-6f)
        assertEquals(-1f, g.right, 1e-6f)
        assertEquals(0f, Mixing.sendGain(Track(id = "b", name = "b")), 0f)
        assertEquals(1f, Mixing.sendGain(t.copy(reverbSendDb = 0.0)), 1e-6f)
    }

    @Test
    fun reverbPresetsAndNativeBlock() {
        ReverbPreset.entries.forEach { p ->
            val r = ReverbSettings.of(p)
            assertEquals(p, r.preset)
            assertTrue(r.decaySeconds in ReverbSettings.MIN_DECAY_S..ReverbSettings.MAX_DECAY_S)
            assertTrue(r.preDelayMs in 0.0..ReverbSettings.MAX_PRE_DELAY_MS)
        }
        assertEquals(ReverbSettings.of(ReverbPreset.ROOM), ReverbSettings())
        val block = Mixing.reverbParams(ReverbSettings(returnDb = ReverbSettings.MIN_RETURN_DB))
        assertEquals(5, block.size)
        assertEquals(0f, block[4], 0f)
    }

    @Test
    fun oldProjectsReadWithNeutralEffects() {
        val json = Json { ignoreUnknownKeys = true }
        val t = json.decodeFromString(Track.serializer(), """{"id":"t","name":"#1"}""")
        assertEquals(TrackEq(), t.eq)
        assertFalse(t.phaseInverted)
        assertEquals(Levels.MIN_DB, t.reverbSendDb, 0.0)
        val m = json.decodeFromString(MixerSettings.serializer(), """{"masterVolumeDb":-3.0}""")
        assertEquals(ReverbSettings(), m.reverb)
        val custom = json.decodeFromString(ReverbSettings.serializer(), """{"size":0.2,"preset":null}""")
        assertNull(custom.preset)
    }
}
