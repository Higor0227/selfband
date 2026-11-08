// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable

/** How a latency calibration was measured (SPEC section 2.4). */
@Serializable
enum class CalibrationMethod {
    /** The output plays impulses into the air and the microphone picks them up. */
    ACOUSTIC,
    /** The output is wired back into the input with a cable. */
    LOOPBACK,
    /** The user plays along with clicks; onsets are averaged. */
    TAP,
}

/** Which input channels a take records (mirrors the engine's record mode). */
@Serializable
enum class InputChannel { FIRST, SECOND, STEREO }

/**
 * Result of a latency calibration.
 *
 * The engine already compensates what stream timestamps can see (see [AlignmentMath]).
 * [residualMs] is the remaining latency that only a measurement reveals (converters,
 * analog path, Bluetooth codec...). It is added on top of the timestamp alignment of
 * every take recorded on this route.
 */
@Serializable
data class LatencyCalibration(
    val method: CalibrationMethod,
    val residualMs: Double,
    /** Total measured round trip (timestamps + residual), for display. */
    val roundTripMs: Double,
    /** Spread of the accepted measurements (max - min), ms. */
    val spreadMs: Double,
    val accepted: Int,
    val attempts: Int,
    val sampleRate: Int,
    val measuredAtMillis: Long,
)

/** Result of the playback-leak test: does the input return what the phone plays? */
@Serializable
data class LeakTestResult(
    val detected: Boolean,
    /** Level of the returned signal relative to what was played, dB. */
    val levelDb: Double?,
    val delayMs: Double?,
    val testedAtMillis: Long,
)

/**
 * Settings remembered for one audio route (output device + input device). Loaded
 * automatically when the same devices are connected again (SPEC section 2.4).
 *
 * The key identifies the devices only to find their saved data: behavior never depends
 * on which device it is, only on what was measured or chosen here (SPEC section 1).
 */
@Serializable
data class DeviceProfile(
    val key: String,
    val outputLabel: String,
    val inputLabel: String,
    val calibration: LatencyCalibration? = null,
    val leak: LeakTestResult? = null,
    /** Null = not chosen yet (use the default for the detected channel count). */
    val inputChannel: InputChannel? = null,
    /** Digital gain applied to the input before metering, monitoring and recording. */
    val inputGainDb: Double = 0.0,
    /** Software monitoring (input sent back to the headphones). Off by default. */
    val monitoring: Boolean = false,
    val monitorGainDb: Double = 0.0,
    /** Sample rate the engine ran at when this profile was last used. */
    val lastSampleRate: Int = 0,
    val updatedAtMillis: Long = 0,
) {
    companion object {
        const val MAX_INPUT_GAIN_DB = 24.0
        const val MIN_INPUT_GAIN_DB = -24.0

        /** Identity of one device for profile lookup (type + product name + address). */
        fun deviceId(device: AudioDevice?): String =
            if (device == null) "default" else "${device.type}:${device.name}:${device.address}"

        fun routeKey(output: AudioDevice?, input: AudioDevice?): String =
            "${deviceId(output)}|${deviceId(input)}"

        fun deviceLabel(device: AudioDevice?): String = when {
            device == null -> "?"
            device.name.isBlank() -> device.type.name
            else -> "${device.type.name} · ${device.name}"
        }
    }
}
