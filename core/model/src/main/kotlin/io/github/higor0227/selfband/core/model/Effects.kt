// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Track EQ (SPEC 2.5): high-pass, low shelf, one peaking band, high shelf, and a bypass.
 * Shelves and the peak at 0 dB change nothing, so a new track sounds untouched.
 */
@Serializable
data class TrackEq(
    /** False = bypass (the settings are kept). */
    val enabled: Boolean = true,
    val highPass: Boolean = false,
    val highPassHz: Double = 80.0,
    val lowShelfHz: Double = 120.0,
    val lowShelfDb: Double = 0.0,
    val peakHz: Double = 1000.0,
    val peakDb: Double = 0.0,
    val peakQ: Double = 1.0,
    val highShelfHz: Double = 8000.0,
    val highShelfDb: Double = 0.0,
) {
    /** True when the EQ cannot change the sound (bypassed or every band neutral). */
    val isNeutral: Boolean
        get() = !enabled || (!highPass && lowShelfDb == 0.0 && peakDb == 0.0 && highShelfDb == 0.0)

    companion object {
        const val MIN_HIGH_PASS_HZ = 20.0
        const val MAX_HIGH_PASS_HZ = 300.0
        const val MIN_LOW_SHELF_HZ = 40.0
        const val MAX_LOW_SHELF_HZ = 500.0
        const val MIN_PEAK_HZ = 60.0
        const val MAX_PEAK_HZ = 12_000.0
        const val MIN_PEAK_Q = 0.3
        const val MAX_PEAK_Q = 8.0
        const val MIN_HIGH_SHELF_HZ = 2_000.0
        const val MAX_HIGH_SHELF_HZ = 16_000.0
        const val MAX_GAIN_DB = 15.0
    }
}

enum class ReverbPreset { SMALL_ROOM, ROOM, HALL, PLATE }

/** The single send reverb on the master bus (SPEC 2.5). */
@Serializable
data class ReverbSettings(
    /** Room size, 0 = small … 1 = large. */
    val size: Double = 0.5,
    /** Decay time (RT60) in seconds. */
    val decaySeconds: Double = 1.2,
    val preDelayMs: Double = 12.0,
    /** High-frequency damping, 0 = bright … 1 = dark. */
    val damping: Double = 0.5,
    /** Wet level returned to the master, in dB. */
    val returnDb: Double = 0.0,
    /** Preset the values came from; null once the user edits one (custom). */
    val preset: ReverbPreset? = ReverbPreset.ROOM,
) {
    companion object {
        const val MIN_DECAY_S = 0.3
        const val MAX_DECAY_S = 8.0
        const val MAX_PRE_DELAY_MS = 200.0
        const val MIN_RETURN_DB = -60.0
        const val MAX_RETURN_DB = 6.0

        fun of(preset: ReverbPreset): ReverbSettings = when (preset) {
            ReverbPreset.SMALL_ROOM -> ReverbSettings(0.25, 0.6, 4.0, 0.6, 0.0, preset)
            ReverbPreset.ROOM -> ReverbSettings(0.5, 1.2, 12.0, 0.5, 0.0, preset)
            ReverbPreset.HALL -> ReverbSettings(0.9, 2.8, 30.0, 0.4, 0.0, preset)
            ReverbPreset.PLATE -> ReverbSettings(0.6, 1.8, 0.0, 0.15, 0.0, preset)
        }
    }
}

/**
 * EQ math with the same RBJ "Audio EQ Cookbook" formulas as the native Equalizer
 * (core/audio/.../Equalizer.h), used to draw the curve and to test the response.
 */
object EqMath {
    /** Normalized biquad (a0 = 1). */
    data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        /** Magnitude in dB at [hz]. */
        fun magnitudeDb(hz: Double, sampleRate: Int): Double {
            val w = 2 * PI * hz / sampleRate
            val c1 = cos(w)
            val s1 = sin(w)
            val c2 = cos(2 * w)
            val s2 = sin(2 * w)
            val nr = b0 + b1 * c1 + b2 * c2
            val ni = -(b1 * s1 + b2 * s2)
            val dr = 1 + a1 * c1 + a2 * c2
            val di = -(a1 * s1 + a2 * s2)
            val power = (nr * nr + ni * ni) / (dr * dr + di * di)
            return 10 * log10(power.coerceAtLeast(1e-30))
        }
    }

    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    fun highPass(fs: Int, hz: Double, q: Double = sqrt(0.5)): Biquad {
        val w = 2 * PI * hz / fs
        val c = cos(w)
        val alpha = sin(w) / (2 * q)
        return norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    fun peaking(fs: Int, hz: Double, q: Double, db: Double): Biquad {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * hz / fs
        val c = cos(w)
        val alpha = sin(w) / (2 * q)
        return norm(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    fun lowShelf(fs: Int, hz: Double, db: Double): Biquad {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * hz / fs
        val c = cos(w)
        val k = 2 * sqrt(a) * (sin(w) / 2 * sqrt(2.0))
        return norm(
            a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k),
            (a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k,
        )
    }

    fun highShelf(fs: Int, hz: Double, db: Double): Biquad {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * hz / fs
        val c = cos(w)
        val k = 2 * sqrt(a) * (sin(w) / 2 * sqrt(2.0))
        return norm(
            a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k),
            (a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k,
        )
    }

    /** The active stages of [eq], in processing order. Empty when bypassed. */
    fun stages(eq: TrackEq, sampleRate: Int): List<Biquad> {
        if (!eq.enabled) return emptyList()
        val maxHz = 0.45 * sampleRate
        fun hz(v: Double) = v.coerceAtMost(maxHz)
        return buildList {
            if (eq.highPass) add(highPass(sampleRate, hz(eq.highPassHz)))
            add(lowShelf(sampleRate, hz(eq.lowShelfHz), eq.lowShelfDb))
            add(peaking(sampleRate, hz(eq.peakHz), eq.peakQ, eq.peakDb))
            add(highShelf(sampleRate, hz(eq.highShelfHz), eq.highShelfDb))
        }
    }

    /** Total EQ response in dB at [hz]. */
    fun responseDb(eq: TrackEq, hz: Double, sampleRate: Int): Double =
        stages(eq, sampleRate).sumOf { it.magnitudeDb(hz, sampleRate) }

    /** [count] log-spaced frequencies from 20 Hz to 20 kHz, for drawing the curve. */
    fun curveFrequencies(count: Int): List<Double> =
        List(count) { i -> 20.0 * 1000.0.pow(i / (count - 1).toDouble()) }

    /**
     * Native parameter block, in the order of Equalizer::Param in Equalizer.h:
     * enabled, highPassOn, highPassHz, lowShelfHz, lowShelfDb, peakHz, peakDb, peakQ,
     * highShelfHz, highShelfDb.
     */
    fun nativeParams(eq: TrackEq): FloatArray = floatArrayOf(
        if (eq.enabled) 1f else 0f,
        if (eq.highPass) 1f else 0f,
        eq.highPassHz.toFloat(),
        eq.lowShelfHz.toFloat(),
        eq.lowShelfDb.toFloat(),
        eq.peakHz.toFloat(),
        eq.peakDb.toFloat(),
        eq.peakQ.toFloat(),
        eq.highShelfHz.toFloat(),
        eq.highShelfDb.toFloat(),
    )
}
