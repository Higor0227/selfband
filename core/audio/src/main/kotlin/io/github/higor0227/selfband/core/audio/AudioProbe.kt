// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio

/**
 * Diagnostics probe: opens real low-latency streams and reports what the device granted.
 * Used by the audio diagnostics screen.
 */
interface AudioProbe {
    suspend fun start(request: ProbeRequest): ProbeStartResult
    suspend fun stop()
    suspend fun snapshot(): ProbeSnapshot
}

/** Platform audio source presets (values match Oboe's InputPreset). */
enum class InputPreset(val oboeValue: Int) {
    GENERIC(1),
    CAMCORDER(5),
    VOICE_RECOGNITION(6),
    UNPROCESSED(9),
    VOICE_PERFORMANCE(10),
}

data class ProbeRequest(
    val withInput: Boolean,
    /** 0 = let the system route. */
    val outputDeviceId: Int = 0,
    /** 0 = let the system route. */
    val inputDeviceId: Int = 0,
    val inputPreset: InputPreset = InputPreset.VOICE_RECOGNITION,
    val inputChannelCount: Int = 1,
)

sealed interface ProbeStartResult {
    data object Started : ProbeStartResult
    data class Failed(val reason: String) : ProbeStartResult
}

enum class AudioApi { UNSPECIFIED, OPENSL_ES, AAUDIO }
enum class SharingMode { EXCLUSIVE, SHARED }
enum class PerformanceMode { NONE, POWER_SAVING, LOW_LATENCY, OTHER }
enum class SampleFormat { I16, FLOAT, I24, I32, OTHER }

data class StreamStats(
    val isOpen: Boolean,
    /** The system closed the stream, e.g. because its device was unplugged. */
    val disconnected: Boolean,
    val api: AudioApi,
    val sampleRate: Int,
    val channelCount: Int,
    val format: SampleFormat,
    val framesPerBurst: Int,
    val bufferSizeFrames: Int,
    val bufferCapacityFrames: Int,
    val sharingMode: SharingMode,
    val performanceMode: PerformanceMode,
    /** null when the API cannot tell (OpenSL ES). */
    val mmap: Boolean?,
    /** null when the API does not count xruns (OpenSL ES). */
    val xruns: Int?,
    /** Oboe's latency estimate for this direction; null when unavailable. */
    val latencyMs: Double?,
    val deviceId: Int,
    val callbacks: Long,
    val frames: Long,
    /** Input only: decaying peak level, linear (1.0 = full scale). */
    val peak: Float,
) {
    val bufferSizeMs: Double
        get() = if (sampleRate > 0) bufferSizeFrames * 1000.0 / sampleRate else 0.0
}

data class ProbeSnapshot(
    val output: StreamStats,
    val input: StreamStats,
)
