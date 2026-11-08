// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlin.math.roundToLong

/**
 * Sync math (SPEC section 3.4).
 *
 * Definitions, all in frames at the project sample rate:
 * - `O_s`, `I_s`: output frames written and input frames read when the take started
 *   (same audio callback), in each stream's own frame counter.
 * - `ioOffset = (t_in - t_out) * sr - pos_in + pos_out`, from one input timestamp
 *   (pos_in captured at time t_in) and one output timestamp (pos_out presented at time
 *   t_out). It is constant while both streams run without glitches.
 *
 * A sound the user hears at project position `p` and plays along with lands in take
 * sample `k = p - P_s - D`, where
 *
 *     D = ioOffset + I_s - O_s - residual
 *
 * so `D` is the take's automatic offset (negative: the take must move earlier) and
 * `-D` is the round trip. `residual` is the latency not visible to timestamps
 * (converters, analog path, Bluetooth codec...), measured by calibration (Phase 1c).
 */
object AlignmentMath {

    /** Automatic offset `D` using stream timestamps. */
    fun offsetFromTimestamps(
        ioOffsetFrames: Double,
        inputStartFrame: Long,
        outputStartFrame: Long,
        residualFrames: Double = 0.0,
    ): Long = (ioOffsetFrames + inputStartFrame - outputStartFrame - residualFrames).roundToLong()

    /** Fallback offset `D` from Oboe's per-direction latency estimates. */
    fun offsetFromEstimates(
        outputLatencyMs: Double,
        inputLatencyMs: Double,
        sampleRate: Int,
        residualFrames: Double = 0.0,
    ): Long = (-(outputLatencyMs + inputLatencyMs) * sampleRate / 1000.0 - residualFrames).roundToLong()

    /**
     * Instantaneous `ioOffset` from one output timestamp and one input timestamp.
     * Times are in nanoseconds on the same clock (CLOCK_MONOTONIC).
     */
    fun ioOffset(
        outputFramePosition: Long,
        outputTimeNanos: Long,
        inputFramePosition: Long,
        inputTimeNanos: Long,
        sampleRate: Int,
    ): Double =
        (inputTimeNanos - outputTimeNanos) * sampleRate / 1e9 - inputFramePosition + outputFramePosition

    fun framesToMs(frames: Double, sampleRate: Int): Double = frames * 1000.0 / sampleRate
    fun msToFrames(ms: Double, sampleRate: Int): Long = (ms * sampleRate / 1000.0).roundToLong()
}
