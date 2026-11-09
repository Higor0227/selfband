// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.calibration

import io.github.higor0227.selfband.core.audio.AudioEngine
import io.github.higor0227.selfband.core.audio.RecordChannelMode
import io.github.higor0227.selfband.core.model.CalibrationMethod
import io.github.higor0227.selfband.core.model.DeviceProfile
import io.github.higor0227.selfband.core.model.LatencyCalibration
import io.github.higor0227.selfband.core.model.LeakTestResult
import io.github.higor0227.selfband.core.model.Levels
import java.io.File
import javax.inject.Inject

/** What a calibration run measures (SPEC 2.4): one of the three latency methods, or the leak test. */
enum class CalibrationTask(val method: CalibrationMethod?) {
    ACOUSTIC(CalibrationMethod.ACOUSTIC),
    LOOPBACK(CalibrationMethod.LOOPBACK),
    TAP(CalibrationMethod.TAP),
    LEAK(null),
}

enum class CalibrationFailure { NO_SIGNAL, INCONSISTENT, INTERRUPTED }

sealed interface CalibrationOutcome {
    data class Calibrated(val calibration: LatencyCalibration, val saved: Boolean) : CalibrationOutcome
    data class Leak(val result: LeakTestResult) : CalibrationOutcome
    data class Failed(val reason: CalibrationFailure) : CalibrationOutcome
}

/**
 * One calibration or leak test on the open route, judged the same way wherever it runs (the
 * recorder or the first-run wizard). Nothing is saved here: the caller stores the result in
 * the route's profile.
 *
 * The engine must be open and idle. This silences the playback tracks and software
 * monitoring; the caller restores tracks, metronome and input processing afterwards.
 */
class RouteCalibrator @Inject constructor(
    private val engine: AudioEngine,
    private val calibrator: LatencyCalibrator,
) {

    suspend fun measure(
        task: CalibrationTask,
        profile: DeviceProfile?,
        workFile: File,
        onProgress: (Float) -> Unit,
    ): CalibrationOutcome {
        val status = engine.status.value
        val sampleRate = status.sampleRate
        // Silence everything the measurement must not hear: tracks and software monitoring.
        engine.setTracks(emptyList())
        engine.setInputProcessing(Levels.dbToLinear(profile?.inputGainDb ?: 0.0).toFloat(), 0f, RecordChannelMode.FIRST)
        val mode = if (status.inputChannels >= 2) RecordChannelMode.STEREO else RecordChannelMode.FIRST
        val kind = if (task == CalibrationTask.TAP) MeasurementKind.TAPS else MeasurementKind.IMPULSES
        val done = calibrator.measure(kind, workFile, sampleRate, mode, onProgress) as? MeasurementResult.Done
            ?: return CalibrationOutcome.Failed(CalibrationFailure.INTERRUPTED)
        val a = done.analysis
        val now = System.currentTimeMillis()

        if (task == CalibrationTask.LEAK) {
            val detected = a.lagFrames != null && a.coherence >= LatencyCalibrator.MIN_LEAK_COHERENCE &&
                a.accepted * 2 >= a.attempts
            return CalibrationOutcome.Leak(
                LeakTestResult(
                    detected = detected,
                    levelDb = if (detected) a.levelDb else null,
                    delayMs = if (detected) done.roundTripMs else null,
                    testedAtMillis = now,
                ),
            )
        }

        val residual = done.residualMs
        val roundTrip = done.roundTripMs
        val failure = when {
            residual == null || roundTrip == null -> CalibrationFailure.NO_SIGNAL
            kind == MeasurementKind.IMPULSES && a.coherence < LatencyCalibrator.MIN_CALIBRATION_COHERENCE ->
                CalibrationFailure.NO_SIGNAL
            kind == MeasurementKind.TAPS && a.accepted < LatencyCalibrator.MIN_TAPS -> CalibrationFailure.NO_SIGNAL
            a.accepted * 2 < a.attempts -> CalibrationFailure.INCONSISTENT
            else -> null
        }
        if (failure != null || residual == null || roundTrip == null) {
            return CalibrationOutcome.Failed(failure ?: CalibrationFailure.NO_SIGNAL)
        }
        return CalibrationOutcome.Calibrated(
            LatencyCalibration(
                method = task.method!!,
                residualMs = residual,
                roundTripMs = roundTrip,
                spreadMs = done.spreadMs,
                accepted = a.accepted,
                attempts = a.attempts,
                sampleRate = sampleRate,
                measuredAtMillis = now,
            ),
            saved = false,
        )
    }
}
