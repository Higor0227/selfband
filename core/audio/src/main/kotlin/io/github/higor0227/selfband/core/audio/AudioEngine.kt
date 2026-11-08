// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio

import io.github.higor0227.selfband.core.model.AlignmentMath
import io.github.higor0227.selfband.core.model.MetronomeSettings
import io.github.higor0227.selfband.core.model.ReverbSettings
import io.github.higor0227.selfband.core.model.StereoGain
import io.github.higor0227.selfband.core.model.TrackEq
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Full-duplex record/playback engine (C++/Oboe). The audio frame position is the master
 * clock of the app.
 */
interface AudioEngine {
    /** Refreshed about every 50 ms while open. */
    val status: StateFlow<EngineStatus>

    suspend fun open(config: EngineConfig): EngineOpenResult
    suspend fun close()

    /** Replaces the playback tracks. Only while idle. */
    suspend fun setTracks(tracks: List<PlaybackTrack>): Boolean

    /**
     * Smooth gain change for the track at [index] in the last [setTracks] list: left/right
     * linear gains on the master bus with volume, pan, mute and solo folded in (see Mixing).
     */
    suspend fun setTrackGain(index: Int, gain: StereoGain)

    /** Master volume (linear, ramped) and clipping protection. Any time. */
    suspend fun setMaster(gain: Float, limiter: Boolean)

    /** EQ of the track at [index] (smoothed, no clicks). Any time. */
    suspend fun setTrackEq(index: Int, eq: TrackEq)

    /** Reverb send of the track at [index], linear, post-fader (see Mixing.sendGain). Any time. */
    suspend fun setTrackSend(index: Int, send: Float)

    /** The master send reverb. Any time. */
    suspend fun setReverb(reverb: ReverbSettings)

    /**
     * Metronome clicks (headphones only). [countInFrames] > 0 also plays count-in clicks for
     * project positions in [-countInFrames, 0). Can change during playback.
     */
    suspend fun setMetronome(settings: MetronomeSettings, countInFrames: Long)

    /**
     * Calibration: plays [signal] (mono, at the engine rate) once per beat at [bpm], starting
     * at project frame 0, instead of the metronome. Only while idle. Call [setMetronome]
     * afterwards to restore the metronome.
     */
    suspend fun setTestClicks(signal: FloatArray, bpm: Double): Boolean

    /**
     * Digital input gain (linear, applied before metering, monitoring and recording) and
     * software monitoring gain (linear, 0 = off) for the channels in [monitorChannel].
     */
    suspend fun setInputProcessing(inputGain: Float, monitorGain: Float, monitorChannel: RecordChannelMode)

    suspend fun play(fromFrame: Long): Boolean

    /** Plays the tracks from [fromFrame] and records the input into [file] (WAV float32). */
    suspend fun record(file: File, fromFrame: Long, channelMode: RecordChannelMode): Boolean

    /** Stops the transport. Returns the take info if it was recording. */
    suspend fun stop(): RecordingResult?
}

data class EngineConfig(
    val sampleRate: Int,
    val inputChannelCount: Int,
    val inputPreset: InputPreset,
    /** 0 = system routing. */
    val outputDeviceId: Int = 0,
    /** 0 = system routing. */
    val inputDeviceId: Int = 0,
)

sealed interface EngineOpenResult {
    data object Opened : EngineOpenResult
    data class Failed(val reason: String) : EngineOpenResult
}

data class PlaybackTrack(
    val file: File,
    val startFrame: Long,
    val gain: StereoGain,
    val eq: TrackEq = TrackEq(),
    /** Linear reverb send (0 = none). */
    val send: Float = 0f,
)

/** Which input channels a take records. */
enum class RecordChannelMode(val nativeValue: Int) { FIRST(0), SECOND(1), STEREO(2) }

enum class TransportState { IDLE, PLAYING, RECORDING }

data class EngineStatus(
    val isOpen: Boolean = false,
    val transport: TransportState = TransportState.IDLE,
    val positionFrames: Long = 0,
    val peakLeft: Float = 0f,
    val peakRight: Float = 0f,
    val outputXruns: Int? = null,
    val inputXruns: Int? = null,
    val underruns: Long = 0,
    val recordedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val outputDisconnected: Boolean = false,
    val inputDisconnected: Boolean = false,
    /** Median ioOffset (see AlignmentMath); null when timestamps are unavailable. */
    val ioOffsetFrames: Double? = null,
    val ioOffsetSpreadFrames: Double = 0.0,
    val outputLatencyMs: Double? = null,
    val inputLatencyMs: Double? = null,
    val sampleRate: Int = 0,
    val inputChannels: Int = 0,
    val outputApi: AudioApi = AudioApi.UNSPECIFIED,
    val inputApi: AudioApi = AudioApi.UNSPECIFIED,
    val outputMmap: Boolean = false,
    val inputMmap: Boolean = false,
    val outputDeviceId: Int = 0,
    val inputDeviceId: Int = 0,
    val outputBufferFrames: Int = 0,
    val outputBurstFrames: Int = 0,
    /** Post-gain output peak per track, in the order of the last setTracks() list. */
    val trackPeaks: List<Float> = emptyList(),
    /** Master output peaks after the limiter (linear, held). */
    val masterPeakLeft: Float = 0f,
    val masterPeakRight: Float = 0f,
    /** Smallest recent limiter gain: 1 = not limiting, 0.5 = −6 dB of reduction. */
    val limiterGain: Float = 1f,
    /** Set while the transport runs and the output clock is known. */
    val presentationClock: PresentationClock? = null,
)

/**
 * Maps CLOCK_MONOTONIC time to the project position being heard (SPEC 3.4/3.5): the video
 * preview follows this clock. Stable while the transport runs without glitches, so it can be
 * evaluated at every display frame although the status refreshes only every ~50 ms.
 */
data class PresentationClock(
    val outputClockFrames: Double,
    val outputStartFrame: Long,
    val startPosition: Long,
    val sampleRate: Int,
    /** True when derived from the buffer size (no output timestamps): approximate. */
    val estimated: Boolean,
) {
    fun positionAt(timeNanos: Long): Double =
        AlignmentMath.projectPositionAt(timeNanos, outputClockFrames, outputStartFrame, startPosition, sampleRate)
}

data class RecordingResult(
    val frames: Long,
    val channels: Int,
    /** P_s: project frame at the playhead when the take started. */
    val startPosition: Long,
    /** O_s: output stream frame counter when the take started. */
    val outputStartFrame: Long,
    /** I_s: input stream frame counter when the take started. */
    val inputStartFrame: Long,
    /** Null when timestamps were unavailable. */
    val ioOffsetFrames: Double?,
    val ioOffsetSpreadFrames: Double,
    val outputLatencyMs: Double?,
    val inputLatencyMs: Double?,
    val xrunsDuring: Int,
    val droppedFrames: Long,
    /**
     * Output clock: `t * sr / 1e9 - clock` is the output frame presented at CLOCK_MONOTONIC
     * time `t` (ns). Null when unknown. See AlignmentMath.projectPositionAt.
     */
    val outputClockFrames: Double? = null,
    /** True when [outputClockFrames] is estimated from the buffer size (no timestamps). */
    val outputClockEstimated: Boolean = false,
)
