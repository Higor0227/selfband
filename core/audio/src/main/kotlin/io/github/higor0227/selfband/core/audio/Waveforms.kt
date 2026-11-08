// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio

import io.github.higor0227.selfband.core.model.Waveform
import java.io.File

/** Peak overviews of take WAV files, for drawing waveforms (SPEC 2.5 offset by waveform). */
interface Waveforms {
    /**
     * The overview of [wav], computed once and then cached next to it and in memory.
     * Runs on a background dispatcher. Null if the file cannot be read.
     */
    suspend fun load(wav: File): Waveform?

    companion object {
        /** The cache sits at `<wav path> + CACHE_SUFFIX`; delete it with the take. */
        const val CACHE_SUFFIX = ".peaks"
    }
}
