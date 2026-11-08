// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.internal

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.higor0227.selfband.core.audio.AudioDeviceMonitor
import io.github.higor0227.selfband.core.model.AudioDevice
import io.github.higor0227.selfband.core.model.AudioDeviceType
import io.github.higor0227.selfband.core.model.AudioSystemInfo
import io.github.higor0227.selfband.core.model.LOG_TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** [AudioDeviceMonitor] based on AudioManager and AudioDeviceCallback. App-lifetime singleton. */
@Singleton
internal class AndroidAudioDeviceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioDeviceMonitor {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _devices = MutableStateFlow(queryDevices())
    override val devices: StateFlow<List<AudioDevice>> = _devices.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            addedDevices.forEach { Log.i(LOG_TAG, "Audio device added: ${it.describe()}") }
            _devices.value = queryDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            removedDevices.forEach { Log.i(LOG_TAG, "Audio device removed: ${it.describe()}") }
            _devices.value = queryDevices()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    override fun systemInfo(): AudioSystemInfo {
        val pm = context.packageManager
        return AudioSystemInfo(
            nativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull(),
            nativeFramesPerBuffer = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull(),
            hasLowLatencyFeature = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY),
            hasProAudioFeature = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO),
            supportsUnprocessedSource =
                audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true",
        )
    }

    private fun queryDevices(): List<AudioDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_ALL).map { it.toModel() }

    private fun AudioDeviceInfo.describe() =
        "id=$id type=${type.toDeviceType()} input=$isSource name=$productName"

    private fun AudioDeviceInfo.toModel() = AudioDevice(
        id = id,
        name = productName?.toString().orEmpty(),
        type = type.toDeviceType(),
        isInput = isSource,
        sampleRates = sampleRates.toList(),
        channelCounts = channelCounts.toList(),
        address = address.orEmpty(),
    )

    // Generic mapping by platform type constant only (never by product name or model).
    private fun Int.toDeviceType(): AudioDeviceType = when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioDeviceType.BUILTIN_MIC
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioDeviceType.BUILTIN_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioDeviceType.BUILTIN_EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioDeviceType.WIRED_HEADSET
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioDeviceType.WIRED_HEADPHONES
        AudioDeviceInfo.TYPE_LINE_ANALOG -> AudioDeviceType.LINE_ANALOG
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> AudioDeviceType.LINE_DIGITAL
        AudioDeviceInfo.TYPE_USB_DEVICE -> AudioDeviceType.USB_DEVICE
        AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceType.USB_HEADSET
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioDeviceType.USB_ACCESSORY
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioDeviceType.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceType.BLUETOOTH_SCO
        AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioDeviceType.BLE_HEADSET
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> AudioDeviceType.BLE_SPEAKER
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> AudioDeviceType.BLE_BROADCAST
        AudioDeviceInfo.TYPE_HEARING_AID -> AudioDeviceType.HEARING_AID
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> AudioDeviceType.HDMI
        AudioDeviceInfo.TYPE_TELEPHONY -> AudioDeviceType.TELEPHONY
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> AudioDeviceType.REMOTE_SUBMIX
        else -> AudioDeviceType.OTHER
    }
}
