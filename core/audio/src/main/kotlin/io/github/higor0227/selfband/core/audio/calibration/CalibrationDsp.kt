// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.calibration

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure DSP for latency calibration and leak detection (SPEC section 2.4). No Android
 * types, so it runs in JVM unit tests.
 *
 * All positions are file frames of the recorded take. `expected[k]` is where impulse k
 * would be if the timestamp alignment were perfect; the measured lag is how much later it
 * really is (the calibration residual).
 */
object CalibrationDsp {

    /** Hann-windowed linear chirp: broadband, sharp autocorrelation, survives small speakers. */
    fun chirp(
        sampleRate: Int,
        durationMs: Double = 10.0,
        startHz: Double = 500.0,
        endHz: Double = 8_000.0,
        amplitude: Float = 0.8f,
    ): FloatArray {
        val n = (sampleRate * durationMs / 1000.0).roundToInt().coerceAtLeast(8)
        val duration = n.toDouble() / sampleRate
        val sweep = (endHz - startHz) / duration
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            (amplitude * window * sin(2 * PI * (startHz * t + sweep * t * t / 2))).toFloat()
        }
    }

    data class RobustStats(val median: Double, val spread: Double, val accepted: Int, val total: Int)

    /**
     * Median of the values after discarding outliers: a value is kept if it is within
     * max(3 * scaled MAD, [minTolerance]) of the median. Spread is max - min of the kept values.
     */
    fun robustStats(values: List<Double>, minTolerance: Double): RobustStats? {
        if (values.isEmpty()) return null
        val med = median(values)
        val mad = median(values.map { abs(it - med) }) * 1.4826
        val tolerance = max(3 * mad, minTolerance)
        val kept = values.filter { abs(it - med) <= tolerance }
        return RobustStats(median(kept), kept.max() - kept.min(), kept.size, values.size)
    }

    fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Measured lag of a series of impulses or taps. */
    data class OffsetAnalysis(
        /** Median lag in frames (found - expected); null when nothing usable was found. */
        val lagFrames: Double?,
        val spreadFrames: Double,
        val accepted: Int,
        val attempts: Int,
        /** Normalized correlation at the best lag, 0..1 (impulses only; 1 for taps). */
        val coherence: Double,
        /** Level of the recorded impulses relative to the played signal, dB (impulses only). */
        val levelDb: Double?,
        /** Input channel the result came from. */
        val channel: Int,
    )

    /**
     * Finds the known [template] at `expected[k] + lag` in the recording, for one common lag
     * in [minLag, maxLag], then refines each impulse within ±[refineFrames] of it. Uses the
     * channel with the clearest result.
     */
    fun analyzeImpulses(
        channels: List<FloatArray>,
        template: FloatArray,
        expected: LongArray,
        minLag: Int,
        maxLag: Int,
        refineFrames: Int,
        minToleranceFrames: Double,
    ): OffsetAnalysis {
        val results = channels.mapIndexed { index, x ->
            analyzeImpulsesOnChannel(x, template, expected, minLag, maxLag, refineFrames, minToleranceFrames, index)
        }
        return results.maxBy { it.coherence }
    }

    private fun analyzeImpulsesOnChannel(
        x: FloatArray,
        t: FloatArray,
        expected: LongArray,
        minLag: Int,
        maxLag: Int,
        refineFrames: Int,
        minToleranceFrames: Double,
        channel: Int,
    ): OffsetAnalysis {
        val m = t.size
        val tt = t.sumOf { it.toDouble() * it }
        // Prefix sums of x^2 for the energy of any segment.
        val energy = DoubleArray(x.size + 1)
        for (i in x.indices) energy[i + 1] = energy[i] + x[i].toDouble() * x[i]
        fun segmentEnergy(start: Long): Double {
            if (start < 0 || start + m > x.size) return -1.0
            return energy[(start + m).toInt()] - energy[start.toInt()]
        }
        fun dot(start: Long): Double {
            val s = start.toInt()
            var acc = 0.0
            for (i in 0 until m) acc += t[i] * x[s + i]
            return acc
        }

        // 1) Common lag: sum of all impulses' correlations, normalized.
        var bestLag = 0
        var bestR = 0.0
        var bestNum = 0.0
        var bestCount = 0
        for (lag in minLag..maxLag) {
            var num = 0.0
            var e = 0.0
            var count = 0
            for (pos in expected) {
                val start = pos + lag
                val se = segmentEnergy(start)
                if (se < 0) continue
                num += dot(start)
                e += se
                count++
            }
            if (count == 0 || e <= 0) continue
            val r = num / sqrt(e * tt * count)
            if (abs(r) > abs(bestR)) {
                bestR = r
                bestLag = lag
                bestNum = num
                bestCount = count
            }
        }
        if (bestCount == 0) return OffsetAnalysis(null, 0.0, 0, expected.size, 0.0, null, channel)
        val gain = bestNum / (bestCount * tt)
        val levelDb = if (gain != 0.0) 20 * log10(abs(gain)) else null

        // 2) Per-impulse refinement around the common lag, with sub-frame interpolation.
        // Correlations first, then the polarity by vote over each impulse's strongest peak:
        // with jittered impulses the common lag may sit on a negative lobe of the chirp's
        // oscillating correlation, so its sign alone is not reliable.
        val windows = expected.map { pos ->
            DoubleArray(2 * refineFrames + 1) { k ->
                val start = pos + bestLag + k - refineFrames
                if (segmentEnergy(start) < 0) Double.NaN else dot(start)
            }
        }
        val vote = windows.sumOf { w -> w.filterNot { it.isNaN() }.maxByOrNull { abs(it) } ?: 0.0 }
        val sign = if (vote >= 0) 1.0 else -1.0

        val lags = mutableListOf<Double>()
        for ((index, pos) in expected.withIndex()) {
            val c = DoubleArray(windows[index].size) { sign * windows[index][it] }
            var best = Double.NEGATIVE_INFINITY
            var bestL = 0
            for (d in -refineFrames..refineFrames) {
                val v = c[d + refineFrames]
                if (!v.isNaN() && v > best) {
                    best = v
                    bestL = d
                }
            }
            if (best == Double.NEGATIVE_INFINITY) continue
            val se = segmentEnergy(pos + bestLag + bestL)
            val r = if (se > 0) best / sqrt(se * tt) else 0.0
            if (r < abs(bestR) * 0.5) continue // this impulse is buried in noise
            var frac = 0.0
            val i = bestL + refineFrames
            if (i in 1 until c.size - 1 && !c[i - 1].isNaN() && !c[i + 1].isNaN()) {
                val denom = c[i - 1] - 2 * c[i] + c[i + 1]
                if (denom < 0) frac = (0.5 * (c[i - 1] - c[i + 1]) / denom).coerceIn(-0.5, 0.5)
            }
            lags += bestLag + bestL + frac
        }
        val stats = robustStats(lags, minToleranceFrames)
            ?: return OffsetAnalysis(null, 0.0, 0, expected.size, abs(bestR), levelDb, channel)
        return OffsetAnalysis(stats.median, stats.spread, stats.accepted, expected.size, abs(bestR), levelDb, channel)
    }

    /**
     * Finds the attack of a tap (hit, strum, clap) near each expected beat. The window is
     * [expected - before, expected + after). A beat counts only if its peak is clearly above
     * the noise floor.
     */
    fun analyzeTaps(
        channels: List<FloatArray>,
        expected: LongArray,
        beforeFrames: Int,
        afterFrames: Int,
        blockFrames: Int,
        minToleranceFrames: Double,
    ): OffsetAnalysis {
        val n = channels.minOf { it.size }
        // Block envelope: max |x| over all channels in blocks of blockFrames.
        val blocks = (n + blockFrames - 1) / blockFrames
        val env = FloatArray(blocks)
        for (c in channels) {
            for (i in 0 until n) {
                val b = i / blockFrames
                val a = abs(c[i])
                if (a > env[b]) env[b] = a
            }
        }
        // Noise floor: before the first window if there is room, else the quiet part overall.
        val firstWindow = ((expected.minOrNull() ?: 0L) - beforeFrames) / blockFrames
        val lead = env.copyOfRange(0, firstWindow.toInt().coerceIn(0, blocks))
        val noise = if (lead.size >= 10) percentile(lead, 0.95) else percentile(env, 0.2)

        val deltas = mutableListOf<Double>()
        for (pos in expected) {
            val from = ((pos - beforeFrames) / blockFrames).toInt().coerceIn(0, blocks)
            val to = ((pos + afterFrames) / blockFrames).toInt().coerceIn(0, blocks)
            if (to - from < 2) continue
            var peakB = from
            for (b in from until to) if (env[b] > env[peakB]) peakB = b
            val peak = env[peakB]
            if (peak < max(4 * noise, 0.005f)) continue // no tap on this beat
            val threshold = max(0.15f * peak, 2 * noise)
            var b = peakB
            while (b > from && env[b - 1] >= threshold) b--
            // Refine inside the block: first sample above the threshold.
            var onset = b.toLong() * blockFrames
            val blockEnd = min(onset + blockFrames, n.toLong())
            for (i in onset until blockEnd) {
                if (channels.any { abs(it[i.toInt()]) >= threshold }) {
                    onset = i
                    break
                }
            }
            deltas += (onset - pos).toDouble()
        }
        val stats = robustStats(deltas, minToleranceFrames)
            ?: return OffsetAnalysis(null, 0.0, 0, expected.size, 0.0, null, 0)
        return OffsetAnalysis(stats.median, stats.spread, stats.accepted, expected.size, 1.0, null, 0)
    }

    private fun percentile(values: FloatArray, p: Double): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sortedArray()
        return sorted[((sorted.size - 1) * p).roundToInt()]
    }
}
