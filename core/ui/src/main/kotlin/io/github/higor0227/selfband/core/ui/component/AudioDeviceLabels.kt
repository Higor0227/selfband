// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.ui.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.higor0227.selfband.core.model.AudioDevice
import io.github.higor0227.selfband.core.model.AudioDeviceType
import io.github.higor0227.selfband.core.ui.R

/** "Alto-falante · SM-S938B": the device kind in the app language, then the name the system reports. */
@Composable
fun audioDeviceLabel(device: AudioDevice?): String {
    if (device == null) return "?"
    val kind = stringResource(device.type.label)
    return if (device.name.isBlank()) kind else "$kind · ${device.name}"
}

@get:StringRes
val AudioDeviceType.label: Int
    get() = when (this) {
        AudioDeviceType.BUILTIN_MIC -> R.string.device_builtin_mic
        AudioDeviceType.BUILTIN_SPEAKER -> R.string.device_builtin_speaker
        AudioDeviceType.BUILTIN_EARPIECE -> R.string.device_builtin_earpiece
        AudioDeviceType.WIRED_HEADSET -> R.string.device_wired_headset
        AudioDeviceType.WIRED_HEADPHONES -> R.string.device_wired_headphones
        AudioDeviceType.LINE_ANALOG -> R.string.device_line_analog
        AudioDeviceType.LINE_DIGITAL -> R.string.device_line_digital
        AudioDeviceType.USB_DEVICE -> R.string.device_usb_device
        AudioDeviceType.USB_HEADSET -> R.string.device_usb_headset
        AudioDeviceType.USB_ACCESSORY -> R.string.device_usb_accessory
        AudioDeviceType.BLUETOOTH_A2DP -> R.string.device_bluetooth_a2dp
        AudioDeviceType.BLUETOOTH_SCO -> R.string.device_bluetooth_sco
        AudioDeviceType.BLE_HEADSET -> R.string.device_ble_headset
        AudioDeviceType.BLE_SPEAKER -> R.string.device_ble_speaker
        AudioDeviceType.BLE_BROADCAST -> R.string.device_ble_broadcast
        AudioDeviceType.HEARING_AID -> R.string.device_hearing_aid
        AudioDeviceType.HDMI -> R.string.device_hdmi
        AudioDeviceType.TELEPHONY -> R.string.device_telephony
        AudioDeviceType.REMOTE_SUBMIX -> R.string.device_remote_submix
        AudioDeviceType.OTHER -> R.string.device_other
    }
