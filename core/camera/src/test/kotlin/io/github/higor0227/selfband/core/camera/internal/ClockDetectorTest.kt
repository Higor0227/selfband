// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.camera.internal

import io.github.higor0227.selfband.core.model.VideoClock
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockDetectorTest {

    private val sleptNs = 3_600_000_000_000L // the device slept 1 h: BOOTTIME is ahead

    private fun ClockDetector.feed(frames: Int, sensorAhead: (Long) -> Long) {
        repeat(frames) { i ->
            val mono = 10_000_000_000L + i * 33_000_000L
            observe(sensorAhead(mono), mono, mono + sleptNs)
        }
    }

    @Test
    fun detectsMonotonic() {
        val d = ClockDetector().apply { reset(declaredRealtime = false) }
        d.feed(10) { mono -> mono - 40_000_000L } // result arrives 40 ms after the frame
        assertEquals(VideoClock.MONOTONIC, d.clock)
    }

    @Test
    fun detectsBoottimeEvenIfDeclaredUnknown() {
        val d = ClockDetector().apply { reset(declaredRealtime = false) }
        d.feed(10) { mono -> mono + sleptNs - 40_000_000L }
        assertEquals(VideoClock.BOOTTIME, d.clock)
    }

    @Test
    fun ambiguousClocksFollowTheDeclaredSource() {
        val d = ClockDetector().apply { reset(declaredRealtime = true) }
        repeat(10) { i ->
            val now = 10_000_000_000L + i * 33_000_000L
            d.observe(now - 40_000_000L, now, now) // never slept: both clocks agree
        }
        assertEquals(VideoClock.BOOTTIME, d.clock)
    }

    @Test
    fun unrelatedClockStaysUnknown() {
        val d = ClockDetector().apply { reset(declaredRealtime = false) }
        d.feed(10) { 123L }
        assertEquals(VideoClock.UNKNOWN, d.clock)
    }
}
