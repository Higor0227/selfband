// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio

import io.github.higor0227.selfband.core.model.AlignmentInfo
import io.github.higor0227.selfband.core.model.AlignmentMath
import io.github.higor0227.selfband.core.model.AlignmentMethod
import io.github.higor0227.selfband.core.model.CalibrationMethod

/** Automatic offset for a finished take plus how it was obtained. */
data class TakeAlignment(val autoOffsetFrames: Long, val info: AlignmentInfo)

/**
 * Computes the automatic alignment of a take (SPEC section 3.4): stream timestamps when
 * available, otherwise Oboe's latency estimates. [residualFrames] comes from calibration
 * (0 when the route is not calibrated).
 */
fun RecordingResult.alignment(
    sampleRate: Int,
    residualFrames: Double = 0.0,
    profileKey: String? = null,
    calibrationMethod: CalibrationMethod? = null,
): TakeAlignment {
    val io = ioOffsetFrames
    val (offset, method) = if (io != null) {
        AlignmentMath.offsetFromTimestamps(io, inputStartFrame, outputStartFrame, residualFrames) to
            AlignmentMethod.TIMESTAMPS
    } else {
        AlignmentMath.offsetFromEstimates(
            outputLatencyMs ?: 0.0, inputLatencyMs ?: 0.0, sampleRate, residualFrames,
        ) to AlignmentMethod.ESTIMATE
    }
    return TakeAlignment(
        autoOffsetFrames = offset,
        info = AlignmentInfo(
            method = method,
            measuredRoundTripFrames = -(offset + residualFrames),
            calibrationResidualFrames = residualFrames,
            outputLatencyEstimateMs = outputLatencyMs,
            inputLatencyEstimateMs = inputLatencyMs,
            xrunsDuringTake = xrunsDuring,
            droppedFrames = droppedFrames,
            profileKey = profileKey,
            calibrationMethod = calibrationMethod,
        ),
    )
}
