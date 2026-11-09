// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.ui.component

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.SelfbandLog

private class Counter {
    var count = 0
    var since = SystemClock.elapsedRealtime()
}

/**
 * With the detailed log on (Settings), logs how many times per second the calling composable
 * recomposes, every [periodMs]. Call it at the top of a screen's composable.
 */
@Composable
fun RecompositionLog(name: String, periodMs: Long = 5_000) {
    val counter = remember { Counter() }
    SideEffect {
        if (!SelfbandLog.verbose) return@SideEffect
        counter.count++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - counter.since
        if (elapsed >= periodMs) {
            Log.d(LOG_TAG, "recompose $name: ${"%.1f".format(counter.count * 1000.0 / elapsed)}/s")
            counter.count = 0
            counter.since = now
        }
    }
}
