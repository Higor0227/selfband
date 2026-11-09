// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio

import io.github.higor0227.selfband.core.model.AudioDevice
import io.github.higor0227.selfband.core.model.AudioDeviceCategory
import io.github.higor0227.selfband.core.model.AudioDeviceType
import io.github.higor0227.selfband.core.model.DeviceProfile

/** The devices the engine actually runs on, and the key of their saved profile. */
data class AudioRoute(val key: String, val output: AudioDevice?, val input: AudioDevice?) {
    /** Software monitoring is never allowed on a loudspeaker (feedback risk, SPEC 2.4). */
    val monitoringAllowed: Boolean
        get() = output != null && output.type !in LOUDSPEAKERS

    val isBluetooth: Boolean
        get() = output?.category == AudioDeviceCategory.BLUETOOTH

    /** Whether the output is a loudspeaker the microphone can hear (acoustic calibration). */
    val outputIsLoudspeaker: Boolean
        get() = output != null && output.type in LOUDSPEAKERS

    /** A fresh profile for this route: nothing measured, software monitoring off (SPEC 2.4). */
    fun newProfile(): DeviceProfile = DeviceProfile(
        key = key,
        outputLabel = DeviceProfile.deviceLabel(output),
        inputLabel = DeviceProfile.deviceLabel(input),
        monitoring = false,
    )

    companion object {
        private val LOUDSPEAKERS = setOf(
            AudioDeviceType.BUILTIN_SPEAKER, AudioDeviceType.BUILTIN_EARPIECE, AudioDeviceType.BLE_SPEAKER,
            AudioDeviceType.HDMI, AudioDeviceType.TELEPHONY, AudioDeviceType.REMOTE_SUBMIX,
        )

        /** The route of the open streams in [status]; [preferredInput] if the input id is not known. */
        fun of(devices: List<AudioDevice>, status: EngineStatus, preferredInput: AudioDevice?): AudioRoute {
            val output = devices.firstOrNull { !it.isInput && it.id == status.outputDeviceId }
            val input = devices.firstOrNull { it.isInput && it.id == status.inputDeviceId } ?: preferredInput
            return AudioRoute(DeviceProfile.routeKey(output, input), output, input)
        }
    }
}
