// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable

/**
 * "How do you record?" from the first-run wizard (SPEC 2.8). A choice of the user, used only
 * to suggest a calibration method and show the matching tips; the engine itself always follows
 * the detected devices.
 */
@Serializable
enum class RecordingSetup {
    /** The phone's own microphone (listening on the speaker or on headphones). */
    PHONE_MIC,
    /** Wired headphones or headset. */
    WIRED,
    /** A USB audio interface, USB mic or pedal with USB audio. */
    USB_INTERFACE,
    /** Bluetooth headphones: listening only, with high latency. */
    BLUETOOTH,
    ;

    /** Whether the devices this setup needs are connected. */
    fun isConnected(devices: List<AudioDevice>): Boolean = when (this) {
        PHONE_MIC -> devices.any { it.isInput && it.type == AudioDeviceType.BUILTIN_MIC }
        WIRED -> devices.any { !it.isInput && it.category == AudioDeviceCategory.WIRED }
        USB_INTERFACE -> devices.any { it.isInput && it.category == AudioDeviceCategory.USB }
        BLUETOOTH -> devices.any { !it.isInput && it.category == AudioDeviceCategory.BLUETOOTH }
    }

    companion object {
        /** A first guess from what is connected: USB input, then Bluetooth, then wired, else the phone. */
        fun detect(devices: List<AudioDevice>): RecordingSetup = when {
            USB_INTERFACE.isConnected(devices) -> USB_INTERFACE
            BLUETOOTH.isConnected(devices) -> BLUETOOTH
            WIRED.isConnected(devices) -> WIRED
            else -> PHONE_MIC
        }
    }
}
