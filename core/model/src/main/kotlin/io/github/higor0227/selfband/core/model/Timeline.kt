// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlin.math.max
import kotlin.math.min

/** Global trim rules (SPEC 2.6): start and end of the project, non-destructive. */
object Trim {
    /** Shortest allowed trimmed project. */
    const val MIN_LENGTH_MS = 100.0

    /**
     * Clamps a requested trim to the project: start in [0, length − min], end in
     * [start + min, length]. Returns the values to store; an end at the project end is
     * stored as null so it follows the project if a longer take is added later.
     */
    fun normalized(project: Project, startFrame: Long, endFrame: Long): Pair<Long, Long?> {
        val length = project.lengthFrames
        val minLength = min(AlignmentMath.msToFrames(MIN_LENGTH_MS, project.sampleRate), length)
        val start = startFrame.coerceIn(0L, length - minLength)
        val end = endFrame.coerceIn(start + minLength, length)
        return start to (if (end >= length) null else end)
    }

    /** Where playback starts: the playhead if it is inside the trim, else the trim start. */
    fun playStart(project: Project, playhead: Long): Long =
        if (playhead >= project.trimStart && playhead < project.trimEnd) playhead else project.trimStart
}

/**
 * Peak overview of a take for drawing (mono: the loudest channel). [peaks] holds the
 * absolute peak of each block of [framesPerPeak] frames; coarser levels are built once so
 * that a zoomed-out view touches few values.
 */
class Waveform(val framesPerPeak: Int, val peaks: FloatArray) {
    val frames: Long get() = peaks.size.toLong() * framesPerPeak

    private val levels: List<FloatArray> = buildList {
        add(peaks)
        var current = peaks
        while (current.size > 1 && size < MAX_LEVELS) {
            val next = FloatArray((current.size + LEVEL_FACTOR - 1) / LEVEL_FACTOR) { i ->
                var m = 0f
                for (j in i * LEVEL_FACTOR until min((i + 1) * LEVEL_FACTOR, current.size)) m = max(m, current[j])
                m
            }
            add(next)
            current = next
        }
    }

    /**
     * Peak over take frames [from, to), exact at the base resolution. The aligned middle of
     * the range is read from coarser levels and only the edges from finer ones, so the cost
     * stays small at any zoom.
     */
    fun peak(from: Long, to: Long): Float {
        var lo = max(from, 0L)
        var hi = min(to, frames)
        if (hi <= lo) return 0f
        var level = 0
        var block = framesPerPeak.toLong()
        var m = 0f
        while (level + 1 < levels.size) {
            val next = block * LEVEL_FACTOR
            val alignedLo = (lo + next - 1) / next * next
            val alignedHi = hi / next * next
            if (alignedLo >= alignedHi) break
            m = max(m, scan(level, block, lo, alignedLo))
            m = max(m, scan(level, block, alignedHi, hi))
            lo = alignedLo
            hi = alignedHi
            level++
            block = next
        }
        return max(m, scan(level, block, lo, hi))
    }

    private fun scan(level: Int, block: Long, from: Long, to: Long): Float {
        if (to <= from) return 0f
        val data = levels[level]
        var m = 0f
        for (i in (from / block).toInt()..((to - 1) / block).toInt().coerceAtMost(data.size - 1)) m = max(m, data[i])
        return m
    }

    companion object {
        const val DEFAULT_FRAMES_PER_PEAK = 64
        private const val LEVEL_FACTOR = 4
        private const val MAX_LEVELS = 8
    }
}
