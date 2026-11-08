// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlin.math.log10
import kotlin.math.pow

object Levels {
    /** Fader bottom: at or below this, the track is silent. */
    const val MIN_DB = -60.0
    const val MAX_DB = 24.0

    fun dbToLinear(db: Double): Double = if (db <= MIN_DB) 0.0 else 10.0.pow(db / 20.0)

    fun linearToDb(linear: Double): Double = if (linear <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(linear)
}
