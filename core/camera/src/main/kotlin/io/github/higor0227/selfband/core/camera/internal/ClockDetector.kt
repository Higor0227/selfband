// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.camera.internal

import io.github.higor0227.selfband.core.model.VideoClock

/**
 * Detects which system clock the camera's sensor timestamps use (SPEC 3.4: check
 * SENSOR_INFO_TIMESTAMP_SOURCE). The declared source is only a hint: each frame's timestamp
 * is compared with CLOCK_MONOTONIC and CLOCK_BOOTTIME read when its result arrives, and the
 * clock for which the frame is a little in the past wins. Pure logic for unit tests.
 */
internal class ClockDetector {
    var clock: VideoClock = VideoClock.UNKNOWN
        private set
    private var declaredRealtime = false
    private var votesMonotonic = 0
    private var votesBoot = 0

    fun reset(declaredRealtime: Boolean) {
        this.declaredRealtime = declaredRealtime
        clock = VideoClock.UNKNOWN
        votesMonotonic = 0
        votesBoot = 0
    }

    /** Returns true when the detected clock changed. */
    @Synchronized
    fun observe(sensorTimestamp: Long, monotonicNow: Long, boottimeNow: Long): Boolean {
        val dm = monotonicNow - sensorTimestamp
        val db = boottimeNow - sensorTimestamp
        val m = dm in 0..MAX_RESULT_DELAY_NS
        val b = db in 0..MAX_RESULT_DELAY_NS
        when {
            // Both plausible: the device has not slept since boot, the clocks agree.
            m && b -> if (declaredRealtime) votesBoot++ else votesMonotonic++
            m -> votesMonotonic++
            b -> votesBoot++
            else -> return false
        }
        val decided = when {
            votesMonotonic >= VOTES && votesMonotonic > votesBoot -> VideoClock.MONOTONIC
            votesBoot >= VOTES && votesBoot > votesMonotonic -> VideoClock.BOOTTIME
            else -> clock
        }
        if (decided == clock) return false
        clock = decided
        return true
    }

    private companion object {
        /** A result arrives well under a second after its exposure started. */
        const val MAX_RESULT_DELAY_NS = 1_000_000_000L
        const val VOTES = 5
    }
}
