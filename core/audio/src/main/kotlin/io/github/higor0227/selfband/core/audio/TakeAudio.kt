// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio

import io.github.higor0227.selfband.core.audio.calibration.WavFloatReader
import java.io.File

/** Simple analyses of recorded take files (off the audio thread). */
object TakeAudio {
    /** Frame index of the loudest sample in a take WAV, e.g. the clap of a sync test. */
    fun peakFrame(file: File): Long? = WavFloatReader.peakFrame(file)
}
