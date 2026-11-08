// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AlignmentMathTest {

    private val sr = 48_000

    /**
     * Simulates a callback at time `now` where the output frame just written (O_s) will be
     * heard after `lOut` and the input frame just read (I_s) was captured `lIn` ago.
     * The take must move earlier by the full round trip.
     */
    @Test
    fun timestampOffsetEqualsMinusRoundTrip() {
        val now = 10_000_000_000L
        val lOutNs = 9_000_000L   // 9 ms
        val lInNs = 4_500_000L    // 4.5 ms
        val oS = 123_456L
        val iS = 789_012L
        val io = AlignmentMath.ioOffset(
            outputFramePosition = oS, outputTimeNanos = now + lOutNs,
            inputFramePosition = iS, inputTimeNanos = now - lInNs,
            sampleRate = sr,
        )
        val d = AlignmentMath.offsetFromTimestamps(io, iS, oS)
        assertEquals(-(13.5 * sr / 1000).toLong(), d) // -648 frames
    }

    /** ioOffset must not depend on which timestamp pair is sampled (streams run in lockstep). */
    @Test
    fun ioOffsetIsInvariantAlongTheStream() {
        val a = AlignmentMath.ioOffset(1_000, 5_000_000_000, 2_000, 4_990_000_000, sr)
        val later = 48_000L // one second later on both streams
        val b = AlignmentMath.ioOffset(1_000 + later, 6_000_000_000, 2_000 + later, 5_990_000_000, sr)
        assertEquals(a, b, 1e-6)
    }

    @Test
    fun residualMovesTakeEarlier() {
        val base = AlignmentMath.offsetFromTimestamps(-500.0, 0, 0)
        val withResidual = AlignmentMath.offsetFromTimestamps(-500.0, 0, 0, residualFrames = 48.0)
        assertEquals(-500L, base)
        assertEquals(-548L, withResidual)
    }

    @Test
    fun estimateFallbackUsesSumOfLatencies() {
        assertEquals(-720L, AlignmentMath.offsetFromEstimates(10.0, 5.0, sr))
    }

    @Test
    fun takeStartCombinesOffsets() {
        val take = Take(
            id = "t", audioFile = "t.wav", channelCount = 1, lengthFrames = 48_000,
            recordStartFrame = 1_000, autoOffsetFrames = -600, manualOffsetFrames = 24,
            alignment = AlignmentInfo(AlignmentMethod.TIMESTAMPS, 600.0), createdAtMillis = 0,
        )
        assertEquals(424L, take.startFrame)
        assertEquals(48_424L, take.endFrame)
    }

    /**
     * The output clock says frame `pos` was heard at `t`. A camera frame captured 250 ms
     * later belongs where the project was being heard 250 ms after that frame.
     */
    @Test
    fun projectPositionAtFollowsTheOutputClock() {
        val tNs = 50_000_000_000L
        val outPos = 1_000_000L
        val clock = tNs.toDouble() * sr / 1e9 - outPos   // t*sr - pos
        val outputStart = 900_000L                         // O_s at record start
        val startPosition = -96_000L                       // P_s (count-in of 2 s)
        // Output frame heard at tNs is outPos; project position = P_s + (outPos - O_s).
        val atT = AlignmentMath.projectPositionAt(tNs, clock, outputStart, startPosition, sr)
        assertEquals((startPosition + outPos - outputStart).toDouble(), atT, 1e-6)
        val later = AlignmentMath.projectPositionAt(tNs + 250_000_000L, clock, outputStart, startPosition, sr)
        assertEquals(atT + 0.25 * sr, later, 1e-6)
    }

    @Test
    fun takeVideoStartIncludesManualOffset() {
        val v = TakeVideo(
            file = "a.mp4", lens = CameraLens.FRONT, width = 1920, height = 1080, rotationDegrees = 270,
            previewMirrored = true, frameRate = 30, frameCount = 300, durationUs = 10_000_000,
            startPositionFrames = 4_800.0, manualOffsetMs = -33.3, clock = VideoClock.MONOTONIC,
        )
        assertEquals(4_800.0 - 33.3 * 48, v.startFrame(sr)!!, 1e-6)
        assertEquals(null, v.copy(startPositionFrames = null).startFrame(sr))
    }
}
