// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export

import io.github.higor0227.selfband.core.model.ExportDefaults
import io.github.higor0227.selfband.core.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportOptionsTest {

    @Test
    fun `default settings give the default export options`() {
        assertEquals(ExportOptions(), ExportOptions.from(ExportDefaults()))
    }

    @Test
    fun `export defaults become the starting options`() {
        val o = ExportOptions.from(
            ExportDefaults(
                video = false,
                codec = VideoCodec.HEVC,
                shortSide = 2160,
                frameRate = 60,
                mixWav = true,
                stems = true,
                wavFloat = true,
            ),
        )
        assertFalse(o.video)
        assertEquals(VideoCodec.HEVC, o.videoOptions.codec)
        assertEquals(VideoResolution.P2160, o.videoOptions.resolution)
        assertEquals(60, o.videoOptions.frameRate)
        assertTrue(o.mixWav)
        assertTrue(o.stems)
        assertEquals(WavFormat.FLOAT_32, o.wavFormat)
    }

    @Test
    fun `unknown sizes and rates fall back to the defaults`() {
        val o = ExportOptions.from(ExportDefaults(shortSide = 999, frameRate = 7))
        assertEquals(VideoResolution.P1080, o.videoOptions.resolution)
        assertEquals(VideoSpec.DEFAULT_FRAME_RATE, o.videoOptions.frameRate)
    }
}
