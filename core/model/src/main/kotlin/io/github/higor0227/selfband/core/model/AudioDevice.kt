// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

/**
 * Generic audio device category. Behavior is always derived from the detected
 * category/capabilities, never from brand or model (SPEC section 1).
 */
enum class AudioDeviceCategory { BUILTIN, WIRED, USB, BLUETOOTH, OTHER }

/** Detected device type, mapped 1:1 from the platform's device type constants. */
enum class AudioDeviceType(val category: AudioDeviceCategory) {
    BUILTIN_MIC(AudioDeviceCategory.BUILTIN),
    BUILTIN_SPEAKER(AudioDeviceCategory.BUILTIN),
    BUILTIN_EARPIECE(AudioDeviceCategory.BUILTIN),
    WIRED_HEADSET(AudioDeviceCategory.WIRED),
    WIRED_HEADPHONES(AudioDeviceCategory.WIRED),
    LINE_ANALOG(AudioDeviceCategory.WIRED),
    LINE_DIGITAL(AudioDeviceCategory.WIRED),
    USB_DEVICE(AudioDeviceCategory.USB),
    USB_HEADSET(AudioDeviceCategory.USB),
    USB_ACCESSORY(AudioDeviceCategory.USB),
    BLUETOOTH_A2DP(AudioDeviceCategory.BLUETOOTH),
    BLUETOOTH_SCO(AudioDeviceCategory.BLUETOOTH),
    BLE_HEADSET(AudioDeviceCategory.BLUETOOTH),
    BLE_SPEAKER(AudioDeviceCategory.BLUETOOTH),
    BLE_BROADCAST(AudioDeviceCategory.BLUETOOTH),
    HEARING_AID(AudioDeviceCategory.BLUETOOTH),
    HDMI(AudioDeviceCategory.OTHER),
    TELEPHONY(AudioDeviceCategory.OTHER),
    REMOTE_SUBMIX(AudioDeviceCategory.OTHER),
    OTHER(AudioDeviceCategory.OTHER),
}

/**
 * An audio input or output as reported by the platform.
 *
 * @property id platform device id (stable only while connected).
 * @property name product name, for display only. Never use it to change behavior.
 * @property sampleRates supported rates; empty means "any" (platform did not restrict).
 * @property channelCounts supported channel counts; empty means "any".
 */
data class AudioDevice(
    val id: Int,
    val name: String,
    val type: AudioDeviceType,
    val isInput: Boolean,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val address: String,
) {
    val category: AudioDeviceCategory get() = type.category
}

/** Platform-wide audio properties reported by the OS. */
data class AudioSystemInfo(
    val nativeSampleRate: Int?,
    val nativeFramesPerBuffer: Int?,
    val hasLowLatencyFeature: Boolean,
    val hasProAudioFeature: Boolean,
    val supportsUnprocessedSource: Boolean,
)
