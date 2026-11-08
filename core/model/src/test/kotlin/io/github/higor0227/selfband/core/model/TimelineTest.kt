// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private val sr = 48_000

    private fun project(lengthFrames: Long, trimStart: Long = 0, trimEnd: Long? = null) = Project(
        id = "p", name = "p", createdAtMillis = 0, updatedAtMillis = 0, sampleRate = sr,
        trimStartFrame = trimStart, trimEndFrame = trimEnd,
        tracks = listOf(
            Track(
                id = "a", name = "a", activeTakeId = "k",
                takes = listOf(
                    Take(
                        id = "k", audioFile = "k.wav", channelCount = 1, lengthFrames = lengthFrames,
                        recordStartFrame = 0, autoOffsetFrames = 0,
                        alignment = AlignmentInfo(AlignmentMethod.TIMESTAMPS, 0.0), createdAtMillis = 0,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun noTrimCoversTheWholeProject() {
        val p = project(480_000)
        assertEquals(0L, p.trimStart)
        assertEquals(480_000L, p.trimEnd)
        assertFalse(p.isTrimmed)
    }

    @Test
    fun trimIsClampedToTheProject() {
        // An end past the project (e.g. after a track was deleted) is clamped.
        val p = project(480_000, trimStart = 48_000, trimEnd = 960_000)
        assertEquals(48_000L, p.trimStart)
        assertEquals(480_000L, p.trimEnd)
        assertTrue(p.isTrimmed)
        val inverted = project(480_000, trimStart = 400_000, trimEnd = 100_000)
        assertEquals(inverted.trimStart, inverted.trimEnd)
    }

    @Test
    fun normalizedKeepsAMinimumLengthAndStoresTheEndAsNull() {
        val p = project(480_000)
        val min = AlignmentMath.msToFrames(Trim.MIN_LENGTH_MS, sr)
        assertEquals(1_000L to 200_000L, Trim.normalized(p, 1_000, 200_000))
        // An end before the start is pushed out to keep the minimum length.
        val (s, e) = Trim.normalized(p, 250_000, 200_000)
        assertEquals(250_000L, s)
        assertEquals(250_000L + min, e)
        // End at (or past) the project end follows the project.
        assertNull(Trim.normalized(p, 0, 480_000).second)
        assertNull(Trim.normalized(p, 0, 999_999).second)
        assertEquals(0L, Trim.normalized(p, -5, 100_000).first)
        // A start at the very end leaves room for the minimum length.
        assertEquals(480_000L - min, Trim.normalized(p, 480_000, 480_000).first)
    }

    @Test
    fun playStartsInsideTheTrim() {
        val p = project(480_000, trimStart = 48_000, trimEnd = 240_000)
        assertEquals(48_000L, Trim.playStart(p, 0))
        assertEquals(100_000L, Trim.playStart(p, 100_000))
        assertEquals(48_000L, Trim.playStart(p, 240_000))
    }

    @Test
    fun oldProjectsReadUntrimmed() {
        val json = Json { ignoreUnknownKeys = true }
        val p = json.decodeFromString(
            Project.serializer(),
            """{"id":"p","name":"p","createdAtMillis":0,"updatedAtMillis":0,"sampleRate":48000}""",
        )
        assertEquals(0L, p.trimStartFrame)
        assertNull(p.trimEndFrame)
    }

    @Test
    fun waveformPeaksMatchABruteForceScanAtEveryZoom() {
        val peaks = FloatArray(1000) { i -> ((i * 7919) % 1000) / 1000f }
        val w = Waveform(64, peaks)
        assertEquals(64_000L, w.frames)
        val ranges = listOf(0L to 64L, 0L to 64_000L, 100L to 5_000L, 63L to 65L, 6_400L to 32_000L, 63_990L to 70_000L)
        for ((from, to) in ranges) {
            var expected = 0f
            for (i in (from / 64).toInt()..((minOf(to, 64_000L) - 1) / 64).toInt()) expected = maxOf(expected, peaks[i])
            assertEquals("[$from, $to)", expected, w.peak(from, to), 0f)
        }
        assertEquals(0f, w.peak(-100, 0), 0f)
        assertEquals(0f, w.peak(64_000, 70_000), 0f)
    }
}
