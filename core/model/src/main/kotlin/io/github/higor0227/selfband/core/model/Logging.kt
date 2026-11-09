// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

/** Logcat tag shared by every module (Kotlin and native). */
const val LOG_TAG = "Selfband"

/**
 * Verbose logging (SPEC 3.8): debug-level lines (per-frame preview statistics, export and
 * proxy details) are written only while this is on. Set from the settings at startup.
 */
object SelfbandLog {
    @Volatile
    var verbose: Boolean = false
}
