// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.calibration

import android.util.Log
import io.github.higor0227.selfband.core.audio.AudioEngine
import io.github.higor0227.selfband.core.audio.RecordChannelMode
import io.github.higor0227.selfband.core.audio.TransportState
import io.github.higor0227.selfband.core.audio.alignment
import io.github.higor0227.selfband.core.model.AlignmentMath
import io.github.higor0227.selfband.core.model.ClickSound
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.MetronomeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToLong

/** What the calibration plays and how the recording is analyzed. */
enum class MeasurementKind {
    /** Test-signal impulses, found by cross-correlation (acoustic, loopback, leak test). */
    IMPULSES,
    /** Metronome clicks the user plays along with; attacks are detected (tap method). */
    TAPS,
}

sealed interface MeasurementResult {
    data class Done(
        val kind: MeasurementKind,
        val analysis: CalibrationDsp.OffsetAnalysis,
        /** Timestamp-based offset D of the measurement take (no residual). */
        val timestampOffsetFrames: Long,
        val sampleRate: Int,
    ) : MeasurementResult {
        val residualMs: Double? get() = analysis.lagFrames?.let { AlignmentMath.framesToMs(it, sampleRate) }
        val spreadMs: Double get() = AlignmentMath.framesToMs(analysis.spreadFrames, sampleRate)
        /** Total round trip = what timestamps see + the residual. */
        val roundTripMs: Double?
            get() = analysis.lagFrames?.let { AlignmentMath.framesToMs(it - timestampOffsetFrames, sampleRate) }
    }

    /** The engine could not record, or the device changed during the measurement. */
    data object Interrupted : MeasurementResult
}

/**
 * Runs one latency measurement through the real engine path (SPEC section 2.4): plays a
 * known signal at known project positions while recording, aligns the recording with the
 * stream timestamps like a normal take, and measures what is still off. That remainder is
 * the calibration residual.
 *
 * The caller must hold the engine idle, with no playback tracks and software monitoring
 * off, and restore the metronome, tracks and input processing afterwards.
 */
class LatencyCalibrator @Inject constructor(private val engine: AudioEngine) {

    suspend fun measure(
        kind: MeasurementKind,
        workFile: File,
        sampleRate: Int,
        channelMode: RecordChannelMode,
        onProgress: (Float) -> Unit,
    ): MeasurementResult {
        val framesPerBeat = sampleRate * 60.0 / BPM
        val beats = if (kind == MeasurementKind.IMPULSES) IMPULSE_COUNT else TAP_COUNT
        val firstAnalyzed = if (kind == MeasurementKind.IMPULSES) 0 else TAP_LISTEN_BEATS
        val lead = (LEAD_SECONDS * sampleRate).roundToLong()
        val end = ((beats - 1) * framesPerBeat).roundToLong() + (TAIL_SECONDS * sampleRate).roundToLong()

        val signal = CalibrationDsp.chirp(sampleRate)
        val configured = when (kind) {
            MeasurementKind.IMPULSES -> engine.setTestClicks(signal, BPM)
            MeasurementKind.TAPS -> {
                engine.setMetronome(
                    MetronomeSettings(
                        enabled = true, bpm = BPM, beatsPerBar = 4, accentFirstBeat = true,
                        sound = ClickSound.WOOD, volume = 0.9f,
                    ),
                    0,
                )
                true
            }
        }
        if (!configured || !engine.record(workFile, -lead, channelMode)) {
            Log.e(LOG_TAG, "Calibration: could not start the measurement")
            return MeasurementResult.Interrupted
        }

        var completed = false
        val result = try {
            completed = withTimeoutOrNull(((end + lead) * 1000 / sampleRate) + TIMEOUT_MARGIN_MS) {
                engine.status.first { s ->
                    onProgress(((s.positionFrames + lead).toFloat() / (end + lead)).coerceIn(0f, 1f))
                    s.transport != TransportState.RECORDING || s.outputDisconnected ||
                        s.inputDisconnected || s.positionFrames >= end
                }.let { it.transport == TransportState.RECORDING && it.positionFrames >= end }
            } ?: false
            withContext(NonCancellable) { engine.stop() }
        } catch (t: Throwable) {
            withContext(NonCancellable) { engine.stop(); workFile.delete() }
            throw t
        }
        if (!completed || result == null || result.frames <= 0) {
            workFile.delete()
            return MeasurementResult.Interrupted
        }

        val offset = result.alignment(sampleRate).autoOffsetFrames
        val takeStart = result.startPosition + offset
        val expected = LongArray(beats - firstAnalyzed) { i ->
            ((i + firstAnalyzed) * framesPerBeat).roundToLong() - takeStart
        }
        val analysis = withContext(Dispatchers.Default) {
            val channels = WavFloatReader.read(workFile)
            workFile.delete()
            if (channels == null) return@withContext null
            when (kind) {
                MeasurementKind.IMPULSES -> CalibrationDsp.analyzeImpulses(
                    channels = channels,
                    template = signal,
                    expected = expected,
                    minLag = -(MAX_EARLY_SECONDS * sampleRate).toInt(),
                    maxLag = (MAX_LATE_SECONDS * sampleRate).toInt(),
                    refineFrames = (0.002 * sampleRate).toInt(),
                    minToleranceFrames = 0.00025 * sampleRate,
                )
                MeasurementKind.TAPS -> CalibrationDsp.analyzeTaps(
                    channels = channels,
                    expected = expected,
                    beforeFrames = (framesPerBeat * 0.35).toInt(),
                    afterFrames = (framesPerBeat * 0.6).toInt(),
                    blockFrames = (0.0005 * sampleRate).toInt().coerceAtLeast(1),
                    minToleranceFrames = 0.008 * sampleRate,
                )
            }
        } ?: return MeasurementResult.Interrupted

        Log.i(
            LOG_TAG,
            "Calibration $kind: lag=${analysis.lagFrames} frames spread=${analysis.spreadFrames} " +
                "accepted=${analysis.accepted}/${analysis.attempts} coherence=${"%.3f".format(analysis.coherence)} " +
                "level=${analysis.levelDb?.let { "%.1f dB".format(it) }} ch=${analysis.channel} D=$offset",
        )
        return MeasurementResult.Done(kind, analysis, offset, sampleRate)
    }

    companion object {
        /** 80 BPM: a whole number of frames per beat at 44.1 and 48 kHz, 0.75 s apart. */
        const val BPM = 80.0
        const val IMPULSE_COUNT = 12
        const val TAP_LISTEN_BEATS = 4
        const val TAP_COUNT = 20
        const val LEAD_SECONDS = 0.5
        const val TAIL_SECONDS = 0.8
        /** Search window for the residual, relative to the timestamp alignment. */
        const val MAX_EARLY_SECONDS = 0.1
        const val MAX_LATE_SECONDS = 0.6
        private const val TIMEOUT_MARGIN_MS = 5_000L

        /** Minimum normalized correlation to trust a calibration. */
        const val MIN_CALIBRATION_COHERENCE = 0.2
        /** Minimum normalized correlation to report a playback leak. */
        const val MIN_LEAK_COHERENCE = 0.12
        const val MIN_TAPS = 6
    }
}
