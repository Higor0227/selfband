// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio

import io.github.higor0227.selfband.core.model.AudioDevice
import io.github.higor0227.selfband.core.model.AudioDeviceCategory
import io.github.higor0227.selfband.core.model.AudioDeviceType
import io.github.higor0227.selfband.core.model.AudioSystemInfo

/**
 * Generic routing decisions based only on detected device categories and capabilities
 * (SPEC section 1: never on brand or model). Profiles (Phase 1c) can override these.
 */
object AudioRouting {

    /** Recording input: USB > wired headset mic > built-in mic. Bluetooth mics are not used. */
    fun preferredInput(devices: List<AudioDevice>): AudioDevice? {
        val inputs = devices.filter { it.isInput }
        return inputs.firstOrNull { it.category == AudioDeviceCategory.USB }
            ?: inputs.firstOrNull { it.type == AudioDeviceType.WIRED_HEADSET }
            ?: inputs.firstOrNull { it.type == AudioDeviceType.BUILTIN_MIC }
    }

    /** Stereo for external inputs that support two channels; mono for the built-in mic. */
    fun inputChannelCount(device: AudioDevice?): Int {
        if (device == null || device.category == AudioDeviceCategory.BUILTIN) return 1
        val counts = device.channelCounts
        return if (counts.isEmpty() || counts.any { it >= 2 }) 2 else 1
    }

    /** Least-processed source the platform reports support for. */
    fun inputPreset(system: AudioSystemInfo): InputPreset =
        if (system.supportsUnprocessedSource) InputPreset.UNPROCESSED else InputPreset.VOICE_RECOGNITION
}
