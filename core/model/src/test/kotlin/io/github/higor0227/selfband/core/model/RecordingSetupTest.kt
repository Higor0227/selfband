// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSetupTest {

    private var nextId = 1
    private fun device(type: AudioDeviceType, input: Boolean) =
        AudioDevice(nextId++, "d", type, input, emptyList(), emptyList(), "")

    private val phone = listOf(
        device(AudioDeviceType.BUILTIN_MIC, true),
        device(AudioDeviceType.BUILTIN_SPEAKER, false),
        device(AudioDeviceType.BUILTIN_EARPIECE, false),
    )

    @Test
    fun `only the phone gives the phone mic`() {
        assertEquals(RecordingSetup.PHONE_MIC, RecordingSetup.detect(phone))
    }

    @Test
    fun `wired headphones are detected from the output`() {
        val devices = phone + device(AudioDeviceType.WIRED_HEADPHONES, false)
        assertEquals(RecordingSetup.WIRED, RecordingSetup.detect(devices))
    }

    @Test
    fun `bluetooth wins over wired and usb input wins over everything`() {
        val bt = phone + device(AudioDeviceType.WIRED_HEADPHONES, false) + device(AudioDeviceType.BLUETOOTH_A2DP, false)
        assertEquals(RecordingSetup.BLUETOOTH, RecordingSetup.detect(bt))
        val usb = bt + device(AudioDeviceType.USB_DEVICE, true) + device(AudioDeviceType.USB_DEVICE, false)
        assertEquals(RecordingSetup.USB_INTERFACE, RecordingSetup.detect(usb))
    }

    @Test
    fun `a usb output alone is not an interface to record from`() {
        val devices = phone + device(AudioDeviceType.USB_HEADSET, false)
        assertFalse(RecordingSetup.USB_INTERFACE.isConnected(devices))
        assertTrue(RecordingSetup.PHONE_MIC.isConnected(devices))
    }

    @Test
    fun `nothing connected matches no setup`() {
        RecordingSetup.entries.forEach { assertFalse(it.isConnected(emptyList())) }
    }
}
