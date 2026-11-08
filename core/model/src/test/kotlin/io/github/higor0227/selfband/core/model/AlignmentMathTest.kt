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
}
