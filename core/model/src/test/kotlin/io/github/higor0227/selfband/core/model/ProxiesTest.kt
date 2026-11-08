// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxiesTest {
    @Test
    fun sizeScalesTheShortSideTo540() {
        assertEquals(960 to 540, Proxies.size(1920, 1080))
        assertEquals(540 to 960, Proxies.size(1080, 1920))
        assertEquals(540 to 960, Proxies.size(2160, 3840))
    }

    @Test
    fun sizeIsEvenAndNeverUpscaled() {
        assertEquals(640 to 480, Proxies.size(640, 480))
        val (w, h) = Proxies.size(1440, 1080) // 720 × 540
        assertEquals(720, w)
        assertEquals(540, h)
        val (w2, h2) = Proxies.size(1000, 1001)
        assertEquals(0, w2 % 2)
        assertEquals(0, h2 % 2)
    }

    @Test
    fun autoModeFollowsCountCapacityAndMeasurement() {
        assertFalse(Proxies.use(ProxyMode.AUTO, 7, overCapacity = false, slow = false))
        assertTrue(Proxies.use(ProxyMode.AUTO, 8, overCapacity = false, slow = false))
        assertTrue(Proxies.use(ProxyMode.AUTO, 5, overCapacity = true, slow = false))
        assertTrue(Proxies.use(ProxyMode.AUTO, 3, overCapacity = false, slow = true))
        assertFalse(Proxies.use(ProxyMode.AUTO, 0, overCapacity = true, slow = true))
    }

    @Test
    fun alwaysAndOffIgnoreTheRest() {
        assertTrue(Proxies.use(ProxyMode.ALWAYS, 1, overCapacity = false, slow = false))
        assertFalse(Proxies.use(ProxyMode.OFF, 16, overCapacity = true, slow = true))
    }

    @Test
    fun settingsDefaults() {
        assertEquals(ProxyMode.AUTO, AppSettings().proxyMode)
        assertEquals("t1.proxy.mp4", Proxies.fileName("t1"))
    }
}
