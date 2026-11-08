// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong
import kotlin.random.Random

class CalibrationDspTest {

    private val sr = 48_000
    private val fpb = sr * 60.0 / 80.0
    private val expected = LongArray(12) { (sr / 2 + it * fpb).roundToLong() }

    /** Recording with the chirp at expected + lag (+ jitter), scaled, plus noise. */
    private fun recording(lag: Int, gain: Float, noise: Float, jitter: Int = 0, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        val x = FloatArray(sr * 11) { (rnd.nextFloat() * 2 - 1) * noise }
        val chirp = CalibrationDsp.chirp(sr)
        for (pos in expected) {
            val at = (pos + lag + if (jitter > 0) rnd.nextInt(-jitter, jitter + 1) else 0).toInt()
            for (i in chirp.indices) x[at + i] += chirp[i] * gain
        }
        return x
    }

    private fun analyze(x: FloatArray) = CalibrationDsp.analyzeImpulses(
        channels = listOf(x),
        template = CalibrationDsp.chirp(sr),
        expected = expected,
        minLag = -4_800,
        maxLag = 28_800,
        refineFrames = 96,
        minToleranceFrames = 12.0,
    )

    @Test
    fun findsLoopbackResidualExactly() {
        val a = analyze(recording(lag = 173, gain = 0.5f, noise = 0.001f))
        assertEquals(173.0, a.lagFrames!!, 0.5)
        assertEquals(12, a.accepted)
        assertTrue(a.coherence > 0.9)
        assertEquals(20 * kotlin.math.log10(0.5), a.levelDb!!, 0.5)
    }

    @Test
    fun findsWeakAcousticSignalAndNegativeLag() {
        val a = analyze(recording(lag = -300, gain = 0.02f, noise = 0.01f))
        assertEquals(-300.0, a.lagFrames!!, 1.0)
        assertTrue(a.coherence > LatencyCalibrator.MIN_CALIBRATION_COHERENCE)
    }

    @Test
    fun invertedPolarityStillFound() {
        val a = analyze(recording(lag = 50, gain = -0.3f, noise = 0.001f))
        assertEquals(50.0, a.lagFrames!!, 0.5)
    }

    @Test
    fun silenceIsNotALeak() {
        val a = analyze(recording(lag = 0, gain = 0f, noise = 0.01f))
        assertTrue("coherence ${a.coherence}", a.coherence < LatencyCalibrator.MIN_LEAK_COHERENCE)
    }

    @Test
    fun spreadReflectsJitter() {
        val a = analyze(recording(lag = 1_000, gain = 0.5f, noise = 0.001f, jitter = 5))
        assertEquals(1_000.0, a.lagFrames!!, 5.0)
        assertTrue("spread ${a.spreadFrames} accepted ${a.accepted} lag ${a.lagFrames}", a.spreadFrames in 1.0..10.5)
    }

    @Test
    fun tapsAverageWithOutlierRejected() {
        val rnd = Random(7)
        val x = FloatArray(sr * 11) { (rnd.nextFloat() * 2 - 1) * 0.002f }
        val offsets = listOf(960, 1_000, 1_020, 980, 1_010, 990, 1_005, 995, 1_000, 6_000, 1_000, 985)
        expected.forEachIndexed { k, pos ->
            val at = (pos + offsets[k]).toInt()
            for (i in 0 until 2_000) x[at + i] += (0.6 * kotlin.math.exp(-i / 300.0) * if (i % 2 == 0) 1 else -1).toFloat()
        }
        val a = CalibrationDsp.analyzeTaps(
            channels = listOf(x),
            expected = expected,
            beforeFrames = (fpb * 0.35).toInt(),
            afterFrames = (fpb * 0.6).toInt(),
            blockFrames = 24,
            minToleranceFrames = 384.0,
        )
        assertNotNull(a.lagFrames)
        assertEquals(997.5, a.lagFrames!!, 10.0)
        assertEquals(11, a.accepted)
    }

    @Test
    fun noTapsGivesNothing() {
        val x = FloatArray(sr * 11) { 0f }
        val a = CalibrationDsp.analyzeTaps(listOf(x), expected, 12_600, 21_600, 24, 384.0)
        assertNull(a.lagFrames)
    }

    @Test
    fun robustStatsDiscardsOutliers() {
        val s = CalibrationDsp.robustStats(listOf(10.0, 11.0, 9.0, 10.5, 50.0), 1.0)!!
        assertEquals(4, s.accepted)
        assertEquals(10.25, s.median, 1e-9)
        assertEquals(2.0, s.spread, 1e-9)
    }
}

