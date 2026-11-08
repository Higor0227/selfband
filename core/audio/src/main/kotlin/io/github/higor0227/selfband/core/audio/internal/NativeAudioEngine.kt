// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.internal

import android.util.Log
import io.github.higor0227.selfband.core.audio.AudioApi
import io.github.higor0227.selfband.core.audio.AudioEngine
import io.github.higor0227.selfband.core.audio.EngineConfig
import io.github.higor0227.selfband.core.audio.EngineOpenResult
import io.github.higor0227.selfband.core.audio.EngineStatus
import io.github.higor0227.selfband.core.audio.PlaybackTrack
import io.github.higor0227.selfband.core.audio.PresentationClock
import io.github.higor0227.selfband.core.audio.RecordChannelMode
import io.github.higor0227.selfband.core.audio.RecordingResult
import io.github.higor0227.selfband.core.audio.TransportState
import io.github.higor0227.selfband.core.model.EqMath
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.MetronomeSettings
import io.github.higor0227.selfband.core.model.Mixing
import io.github.higor0227.selfband.core.model.ReverbSettings
import io.github.higor0227.selfband.core.model.StereoGain
import io.github.higor0227.selfband.core.model.TrackEq
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** [AudioEngine] backed by src/main/cpp/engine. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class NativeAudioEngine @Inject constructor() : AudioEngine {

    private val handle: Long = nativeCreate()

    // All native control calls run on one thread, in order.
    private val control = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val _status = MutableStateFlow(EngineStatus())
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    override suspend fun open(config: EngineConfig): EngineOpenResult = withContext(control) {
        val code = nativeOpen(
            handle, config.sampleRate, config.outputDeviceId, config.inputDeviceId,
            config.inputChannelCount, config.inputPreset.oboeValue,
        )
        if (code != 0) {
            Log.e(LOG_TAG, "Engine open failed ($code) for $config")
            _status.value = EngineStatus()
            return@withContext EngineOpenResult.Failed(NativeAudioProbe.resultText(code))
        }
        Log.i(LOG_TAG, "Engine opened: $config")
        startPolling()
        EngineOpenResult.Opened
    }

    override suspend fun close(): Unit = withContext(control) {
        pollJob?.cancel()
        pollJob = null
        nativeClose(handle)
        _status.value = EngineStatus()
        Log.i(LOG_TAG, "Engine closed")
    }

    @Volatile private var trackCount = 0

    override suspend fun setMetronome(settings: MetronomeSettings, countInFrames: Long): Unit =
        withContext(control) {
            nativeSetMetronome(
                handle, settings.enabled, settings.bpm, settings.beatsPerBar, settings.accentFirstBeat,
                settings.sound.ordinal, settings.volume, -countInFrames.coerceAtLeast(0),
            )
        }

    override suspend fun setTestClicks(signal: FloatArray, bpm: Double): Boolean = withContext(control) {
        if (!nativeSetTestSignal(handle, signal)) return@withContext false
        nativeSetMetronome(handle, true, bpm, 1, false, TEST_SIGNAL_SOUND, 1f, 0)
        true
    }

    override suspend fun setInputProcessing(
        inputGain: Float,
        monitorGain: Float,
        monitorChannel: RecordChannelMode,
    ): Unit = withContext(control) {
        nativeSetInputProcessing(handle, inputGain, monitorGain, monitorChannel.nativeValue)
    }

    override suspend fun setTracks(tracks: List<PlaybackTrack>): Boolean = withContext(control) {
        trackCount = tracks.size
        nativeSetTracks(
            handle,
            tracks.map { it.file.absolutePath }.toTypedArray(),
            tracks.map { it.startFrame }.toLongArray(),
            tracks.flatMap { listOf(it.gain.left, it.gain.right) }.toFloatArray(),
            tracks.flatMap { EqMath.nativeParams(it.eq).asList() }.toFloatArray(),
            tracks.map { it.send }.toFloatArray(),
        )
    }

    override suspend fun setTrackGain(index: Int, gain: StereoGain): Unit = withContext(control) {
        nativeSetTrackGain(handle, index, gain.left, gain.right)
    }

    override suspend fun setMaster(gain: Float, limiter: Boolean): Unit = withContext(control) {
        nativeSetMaster(handle, gain, limiter)
    }

    override suspend fun setTrackEq(index: Int, eq: TrackEq): Unit = withContext(control) {
        nativeSetTrackEq(handle, index, EqMath.nativeParams(eq))
    }

    override suspend fun setTrackSend(index: Int, send: Float): Unit = withContext(control) {
        nativeSetTrackSend(handle, index, send)
    }

    override suspend fun setReverb(reverb: ReverbSettings): Unit = withContext(control) {
        nativeSetReverb(handle, Mixing.reverbParams(reverb))
    }

    override suspend fun play(fromFrame: Long): Boolean = withContext(control) {
        nativeStartTransport(handle, fromFrame, null, 0)
    }

    override suspend fun record(file: File, fromFrame: Long, channelMode: RecordChannelMode): Boolean =
        withContext(control) {
            nativeStartTransport(handle, fromFrame, file.absolutePath, channelMode.nativeValue)
        }

    override suspend fun stop(): RecordingResult? = withContext(control) {
        val r = DoubleArray(REC_COUNT) { Double.NaN }
        nativeStopTransport(handle, r)
        if (r[REC_FRAMES].isNaN()) return@withContext null
        RecordingResult(
            frames = r[REC_FRAMES].toLong(),
            channels = r[REC_CHANNELS].toInt(),
            startPosition = r[REC_START_POSITION].toLong(),
            outputStartFrame = r[REC_OUTPUT_START].toLong(),
            inputStartFrame = r[REC_INPUT_START].toLong(),
            ioOffsetFrames = r[REC_IO_OFFSET].takeIf { r[REC_IO_VALID] == 1.0 },
            ioOffsetSpreadFrames = r[REC_IO_SPREAD],
            outputLatencyMs = r[REC_OUT_LATENCY].takeIf { it >= 0 },
            inputLatencyMs = r[REC_IN_LATENCY].takeIf { it >= 0 },
            xrunsDuring = r[REC_XRUNS].toInt(),
            droppedFrames = r[REC_DROPPED].toLong(),
            outputClockFrames = r[REC_OUT_CLOCK].takeIf { r[REC_OUT_CLOCK_VALID] > 0 },
            outputClockEstimated = r[REC_OUT_CLOCK_VALID] == 2.0,
        )
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val v = DoubleArray(ST_COUNT)
            while (isActive) {
                val peaks = FloatArray(trackCount)
                val n = withContext(control) {
                    nativeGetStatus(handle, v)
                    nativeGetTrackPeaks(handle, peaks)
                }
                _status.value = v.toStatus().copy(trackPeaks = peaks.take(n))
                delay(POLL_MS)
            }
        }
    }

    private fun DoubleArray.toStatus() = EngineStatus(
        isOpen = this[ST_OPEN] == 1.0,
        transport = when (this[ST_TRANSPORT].toInt()) {
            1 -> TransportState.PLAYING
            2 -> TransportState.RECORDING
            else -> TransportState.IDLE
        },
        positionFrames = this[ST_POSITION].toLong(),
        peakLeft = this[ST_PEAK_L].toFloat(),
        peakRight = this[ST_PEAK_R].toFloat(),
        outputXruns = this[ST_OUT_XRUNS].toInt().takeIf { it >= 0 },
        inputXruns = this[ST_IN_XRUNS].toInt().takeIf { it >= 0 },
        underruns = this[ST_UNDERRUNS].toLong(),
        recordedFrames = this[ST_REC_FRAMES].toLong(),
        droppedFrames = this[ST_REC_DROPPED].toLong(),
        outputDisconnected = this[ST_OUT_DISCONNECTED] == 1.0,
        inputDisconnected = this[ST_IN_DISCONNECTED] == 1.0,
        ioOffsetFrames = this[ST_IO_OFFSET].takeIf { this[ST_IO_VALID] == 1.0 },
        ioOffsetSpreadFrames = this[ST_IO_SPREAD],
        outputLatencyMs = this[ST_OUT_LATENCY].takeIf { it >= 0 },
        inputLatencyMs = this[ST_IN_LATENCY].takeIf { it >= 0 },
        sampleRate = this[ST_SAMPLE_RATE].toInt(),
        inputChannels = this[ST_INPUT_CHANNELS].toInt(),
        outputApi = this[ST_OUT_API].toApi(),
        inputApi = this[ST_IN_API].toApi(),
        outputMmap = this[ST_OUT_MMAP] == 1.0,
        inputMmap = this[ST_IN_MMAP] == 1.0,
        outputDeviceId = this[ST_OUT_DEVICE].toInt(),
        inputDeviceId = this[ST_IN_DEVICE].toInt(),
        outputBufferFrames = this[ST_OUT_BUFFER].toInt(),
        outputBurstFrames = this[ST_OUT_BURST].toInt(),
        presentationClock = if (this[ST_TRANSPORT].toInt() != 0 && this[ST_OUT_CLOCK_VALID] > 0) {
            PresentationClock(
                outputClockFrames = this[ST_OUT_CLOCK],
                outputStartFrame = this[ST_OUTPUT_START].toLong(),
                startPosition = this[ST_START_POSITION].toLong(),
                sampleRate = this[ST_SAMPLE_RATE].toInt(),
                estimated = this[ST_OUT_CLOCK_VALID] == 2.0,
            )
        } else {
            null
        },
        masterPeakLeft = this[ST_MASTER_PEAK_L].toFloat(),
        masterPeakRight = this[ST_MASTER_PEAK_R].toFloat(),
        limiterGain = if (this[ST_OPEN] == 1.0) this[ST_LIMITER_GAIN].toFloat() else 1f,
    )

    private fun Double.toApi() = when (toInt()) {
        1 -> AudioApi.OPENSL_ES
        2 -> AudioApi.AAUDIO
        else -> AudioApi.UNSPECIFIED
    }

    // Indices must match EngineStat / RecResultField in engine/AudioEngine.h.
    private companion object {
        const val POLL_MS = 50L
        /** AudioEngine::kTestSignalSound. */
        const val TEST_SIGNAL_SOUND = 3

        const val ST_OPEN = 0
        const val ST_TRANSPORT = 1
        const val ST_POSITION = 2
        const val ST_PEAK_L = 3
        const val ST_PEAK_R = 4
        const val ST_OUT_XRUNS = 5
        const val ST_IN_XRUNS = 6
        const val ST_UNDERRUNS = 7
        const val ST_REC_FRAMES = 8
        const val ST_REC_DROPPED = 9
        const val ST_OUT_DISCONNECTED = 10
        const val ST_IN_DISCONNECTED = 11
        const val ST_IO_OFFSET = 12
        const val ST_IO_VALID = 13
        const val ST_IO_SPREAD = 14
        const val ST_OUT_LATENCY = 15
        const val ST_IN_LATENCY = 16
        const val ST_SAMPLE_RATE = 17
        const val ST_INPUT_CHANNELS = 18
        const val ST_OUT_API = 19
        const val ST_IN_API = 20
        const val ST_OUT_MMAP = 21
        const val ST_IN_MMAP = 22
        const val ST_OUT_DEVICE = 23
        const val ST_IN_DEVICE = 24
        const val ST_OUT_BUFFER = 25
        const val ST_OUT_BURST = 26
        const val ST_OUT_CLOCK = 27
        const val ST_OUT_CLOCK_VALID = 28
        const val ST_OUTPUT_START = 29
        const val ST_START_POSITION = 30
        const val ST_MASTER_PEAK_L = 31
        const val ST_MASTER_PEAK_R = 32
        const val ST_LIMITER_GAIN = 33
        const val ST_COUNT = 34

        const val REC_FRAMES = 0
        const val REC_CHANNELS = 1
        const val REC_START_POSITION = 2
        const val REC_OUTPUT_START = 3
        const val REC_INPUT_START = 4
        const val REC_IO_OFFSET = 5
        const val REC_IO_VALID = 6
        const val REC_IO_SPREAD = 7
        const val REC_OUT_LATENCY = 8
        const val REC_IN_LATENCY = 9
        const val REC_XRUNS = 10
        const val REC_DROPPED = 11
        const val REC_OUT_CLOCK = 12
        const val REC_OUT_CLOCK_VALID = 13
        const val REC_COUNT = 14

        init {
            System.loadLibrary("selfband_audio")
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeOpen(
            handle: Long, sampleRate: Int, outputDeviceId: Int, inputDeviceId: Int,
            inputChannelCount: Int, inputPreset: Int,
        ): Int
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeSetTracks(
            handle: Long, paths: Array<String>, startFrames: LongArray, gains: FloatArray,
            eqs: FloatArray, sends: FloatArray,
        ): Boolean
        @JvmStatic private external fun nativeSetTrackEq(handle: Long, index: Int, params: FloatArray)
        @JvmStatic private external fun nativeSetTrackSend(handle: Long, index: Int, send: Float)
        @JvmStatic private external fun nativeSetReverb(handle: Long, params: FloatArray)
        @JvmStatic private external fun nativeSetTrackGain(handle: Long, index: Int, gainL: Float, gainR: Float)
        @JvmStatic private external fun nativeSetMaster(handle: Long, gain: Float, limiter: Boolean)
        @JvmStatic private external fun nativeStartTransport(
            handle: Long, fromFrame: Long, recordPath: String?, channelMode: Int,
        ): Boolean
        @JvmStatic private external fun nativeStopTransport(handle: Long, result: DoubleArray)
        @JvmStatic private external fun nativeGetStatus(handle: Long, out: DoubleArray)
        @JvmStatic private external fun nativeSetTestSignal(handle: Long, samples: FloatArray): Boolean
        @JvmStatic private external fun nativeSetInputProcessing(
            handle: Long, inputGain: Float, monitorGain: Float, monitorChannelMode: Int,
        )
        @JvmStatic private external fun nativeGetTrackPeaks(handle: Long, out: FloatArray): Int
        @JvmStatic private external fun nativeSetMetronome(
            handle: Long, enabled: Boolean, bpm: Double, beatsPerBar: Int, accent: Boolean,
            sound: Int, volume: Float, countInStartFrame: Long,
        )
    }
}
