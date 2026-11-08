// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.internal

import android.util.Log
import io.github.higor0227.selfband.core.audio.AudioApi
import io.github.higor0227.selfband.core.audio.AudioProbe
import io.github.higor0227.selfband.core.audio.PerformanceMode
import io.github.higor0227.selfband.core.audio.ProbeRequest
import io.github.higor0227.selfband.core.audio.ProbeSnapshot
import io.github.higor0227.selfband.core.audio.ProbeStartResult
import io.github.higor0227.selfband.core.audio.SampleFormat
import io.github.higor0227.selfband.core.audio.SharingMode
import io.github.higor0227.selfband.core.audio.StreamStats
import io.github.higor0227.selfband.core.model.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** [AudioProbe] backed by the C++ Oboe engine (src/main/cpp/probe). */
@Singleton
internal class NativeAudioProbe @Inject constructor() : AudioProbe {

    private val handle: Long = nativeCreate()

    // Opening/closing streams can block for tens of milliseconds: keep it off the main thread.
    override suspend fun start(request: ProbeRequest): ProbeStartResult = withContext(Dispatchers.IO) {
        val code = nativeStart(
            handle,
            request.withInput,
            request.outputDeviceId,
            request.inputDeviceId,
            request.inputPreset.oboeValue,
            request.inputChannelCount,
        )
        if (code == 0) {
            Log.i(LOG_TAG, "Probe started: $request")
            ProbeStartResult.Started
        } else {
            val text = nativeResultText(code)
            Log.e(LOG_TAG, "Probe start failed ($code): $text")
            ProbeStartResult.Failed(text)
        }
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        nativeStop(handle)
        Log.i(LOG_TAG, "Probe stopped")
    }

    override suspend fun snapshot(): ProbeSnapshot = withContext(Dispatchers.IO) {
        val values = DoubleArray(STAT_COUNT * 2)
        nativeGetStats(handle, values)
        ProbeSnapshot(
            output = values.toStreamStats(0),
            input = values.toStreamStats(STAT_COUNT),
        )
    }

    private fun DoubleArray.toStreamStats(offset: Int): StreamStats {
        fun v(field: Int) = this[offset + field]
        return StreamStats(
            isOpen = v(OPEN) == 1.0,
            disconnected = v(DISCONNECTED) == 1.0,
            api = when (v(API).toInt()) {
                1 -> AudioApi.OPENSL_ES
                2 -> AudioApi.AAUDIO
                else -> AudioApi.UNSPECIFIED
            },
            sampleRate = v(SAMPLE_RATE).toInt(),
            channelCount = v(CHANNEL_COUNT).toInt(),
            format = when (v(FORMAT).toInt()) {
                1 -> SampleFormat.I16
                2 -> SampleFormat.FLOAT
                3 -> SampleFormat.I24
                4 -> SampleFormat.I32
                else -> SampleFormat.OTHER
            },
            framesPerBurst = v(FRAMES_PER_BURST).toInt(),
            bufferSizeFrames = v(BUFFER_SIZE).toInt(),
            bufferCapacityFrames = v(BUFFER_CAPACITY).toInt(),
            sharingMode = if (v(SHARING_MODE).toInt() == 0) SharingMode.EXCLUSIVE else SharingMode.SHARED,
            performanceMode = when (v(PERFORMANCE_MODE).toInt()) {
                10 -> PerformanceMode.NONE
                11 -> PerformanceMode.POWER_SAVING
                12 -> PerformanceMode.LOW_LATENCY
                else -> PerformanceMode.OTHER
            },
            mmap = when (v(MMAP).toInt()) {
                1 -> true
                0 -> false
                else -> null
            },
            xruns = v(XRUNS).toInt().takeIf { it >= 0 },
            latencyMs = v(LATENCY_MS).takeIf { it >= 0.0 },
            deviceId = v(DEVICE_ID).toInt(),
            callbacks = v(CALLBACKS).toLong(),
            frames = v(FRAMES).toLong(),
            peak = v(PEAK).toFloat(),
        )
    }

    // Field indices: must match StatField in probe/AudioProbe.h.
    companion object {
        /** Human-readable text for an oboe::Result code. */
        fun resultText(code: Int): String = nativeResultText(code)

        const val OPEN = 0
        const val API = 1
        const val SAMPLE_RATE = 2
        const val CHANNEL_COUNT = 3
        const val FORMAT = 4
        const val FRAMES_PER_BURST = 5
        const val BUFFER_SIZE = 6
        const val BUFFER_CAPACITY = 7
        const val SHARING_MODE = 8
        const val PERFORMANCE_MODE = 9
        const val MMAP = 10
        const val XRUNS = 11
        const val LATENCY_MS = 12
        const val DEVICE_ID = 13
        const val CALLBACKS = 14
        const val FRAMES = 15
        const val PEAK = 16
        const val DISCONNECTED = 17
        const val STAT_COUNT = 18

        init {
            System.loadLibrary("selfband_audio")
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeStart(
            handle: Long,
            withInput: Boolean,
            outputDeviceId: Int,
            inputDeviceId: Int,
            inputPreset: Int,
            inputChannelCount: Int,
        ): Int
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativeGetStats(handle: Long, out: DoubleArray)
        @JvmStatic private external fun nativeResultText(result: Int): String
    }
}
