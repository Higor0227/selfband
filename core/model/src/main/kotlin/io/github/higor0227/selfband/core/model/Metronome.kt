// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

/** Click sounds synthesized by the engine (index = native sound id). */
@Serializable
enum class ClickSound { BEEP, WOOD, TICK }

/**
 * Metronome (SPEC section 2.3). Clicks are heard only in the headphones: they are never
 * recorded and never exported. Beat 0 is at project frame 0; the BPM counts [beatUnit]
 * notes (e.g. eighth notes in 6/8).
 */
@Serializable
data class MetronomeSettings(
    val enabled: Boolean = false,
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val beatUnit: Int = 4,
    val accentFirstBeat: Boolean = true,
    /** Bars of clicks before recording starts (0..4). Plays even if [enabled] is false. */
    val countInBars: Int = 1,
    val sound: ClickSound = ClickSound.BEEP,
    val volume: Float = 0.7f,
    val visualFlash: Boolean = false,
) {
    fun framesPerBeat(sampleRate: Int): Double = sampleRate * 60.0 / bpm

    fun countInFrames(sampleRate: Int): Long =
        (countInBars * beatsPerBar * framesPerBeat(sampleRate)).roundToLong()

    /** Beat index (0-based, negative during count-in) at a project position. */
    fun beatAt(positionFrames: Long, sampleRate: Int): Long =
        kotlin.math.floor(positionFrames / framesPerBeat(sampleRate)).toLong()

    fun normalized(): MetronomeSettings = copy(
        bpm = bpm.coerceIn(MIN_BPM, MAX_BPM),
        beatsPerBar = beatsPerBar.coerceIn(1, 16),
        countInBars = countInBars.coerceIn(0, MAX_COUNT_IN_BARS),
        volume = volume.coerceIn(0f, 1f),
    )

    companion object {
        const val MIN_BPM = 30.0
        const val MAX_BPM = 300.0
        const val MAX_COUNT_IN_BARS = 4

        /**
         * Tap tempo: BPM from tap times (ms), using the most recent intervals.
         * Returns null until there are at least two taps.
         */
        fun bpmFromTaps(tapTimesMs: List<Long>, maxIntervals: Int = 4): Double? {
            if (tapTimesMs.size < 2) return null
            val intervals = tapTimesMs.zipWithNext { a, b -> b - a }.takeLast(maxIntervals)
            val avg = intervals.average()
            if (avg <= 0) return null
            return (60_000.0 / avg).coerceIn(MIN_BPM, MAX_BPM)
        }
    }
}
