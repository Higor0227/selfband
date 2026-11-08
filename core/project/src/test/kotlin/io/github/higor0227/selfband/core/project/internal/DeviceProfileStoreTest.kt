// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import io.github.higor0227.selfband.core.model.CalibrationMethod
import io.github.higor0227.selfband.core.model.DeviceProfile
import io.github.higor0227.selfband.core.model.InputChannel
import io.github.higor0227.selfband.core.model.LatencyCalibration
import io.github.higor0227.selfband.core.model.LeakTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceProfileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun roundTrip() {
        val file = tmp.root.resolve("device_profiles.json")
        val store = DeviceProfileStore(file)
        assertTrue(store.load().isEmpty())
        val profile = DeviceProfile(
            key = "USB_DEVICE:Interface:card=1|USB_DEVICE:Interface:card=1",
            outputLabel = "USB_DEVICE · Interface",
            inputLabel = "USB_DEVICE · Interface",
            calibration = LatencyCalibration(CalibrationMethod.LOOPBACK, 2.4, 11.9, 0.1, 12, 12, 48_000, 1L),
            leak = LeakTestResult(true, -18.0, 3.0, 2L),
            inputChannel = InputChannel.SECOND,
            inputGainDb = 6.0,
        )
        store.save(listOf(profile))
        assertEquals(listOf(profile), DeviceProfileStore(file).load())
    }

    @Test
    fun corruptFileLoadsEmpty() {
        val file = tmp.root.resolve("device_profiles.json")
        file.writeText("{ not json")
        assertTrue(DeviceProfileStore(file).load().isEmpty())
    }
}
