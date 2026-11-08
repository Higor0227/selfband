// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio

import io.github.higor0227.selfband.core.model.AudioDevice
import io.github.higor0227.selfband.core.model.AudioSystemInfo
import kotlinx.coroutines.flow.StateFlow

/** Detects audio inputs/outputs and keeps the list current as devices come and go. */
interface AudioDeviceMonitor {
    /** All currently connected inputs and outputs. */
    val devices: StateFlow<List<AudioDevice>>

    fun systemInfo(): AudioSystemInfo
}
