// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export

import io.github.higor0227.selfband.core.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSpecTest {

    @Test
    fun sizesFollowTheCanvasWithA1080ShortSide() {
        VideoSpec.forAspect(9f / 16f).let { assertEquals(1080 to 1920, it.width to it.height) }
        VideoSpec.forAspect(16f / 9f).let { assertEquals(1920 to 1080, it.width to it.height) }
        VideoSpec.forAspect(1f).let { assertEquals(1080 to 1080, it.width to it.height) }
        VideoSpec.forAspect(9f / 16f, shortSide = 720).let { assertEquals(720 to 1280, it.width to it.height) }
    }

    @Test
    fun sidesAreEven() {
        val spec = VideoSpec.forAspect(4f / 3f, shortSide = 1081)
        assertEquals(0, spec.width % 2)
        assertEquals(0, spec.height % 2)
    }

    @Test
    fun automaticBitrateScalesWithPixelsAndFrameRate() {
        assertEquals(12_500_000, VideoSpec.forAspect(9f / 16f).bitRate)
        assertEquals(25_000_000, VideoSpec.autoBitRate(1080, 1920, 60))
        assertEquals(5_500_000, VideoSpec.autoBitRate(720, 1280, 30))
        assertEquals(30, VideoSpec.forAspect(1f).frameRate)
    }

    @Test
    fun optionsResolveToSizeCodecAndBitrate() {
        val hevc4k = VideoSpec.of(9f / 16f, VideoOptions(VideoCodec.HEVC, VideoResolution.P2160, 30))
        assertEquals(2160 to 3840, hevc4k.width to hevc4k.height)
        assertEquals("video/hevc", hevc4k.mimeType)
        assertEquals(30_000_000, hevc4k.bitRate)                 // 49.8 Mbps x 0.6
        assertEquals(7_500_000, VideoSpec.of(9f / 16f, VideoOptions(codec = VideoCodec.HEVC)).bitRate)
        val manual = VideoSpec.of(16f / 9f, VideoOptions(resolution = VideoResolution.P720, frameRate = 60, bitRate = 8_000_000))
        assertEquals(Triple(1280, 720, 60), Triple(manual.width, manual.height, manual.frameRate))
        assertEquals(8_000_000, manual.bitRate)
    }

    @Test
    fun estimatedSizeCountsVideoAndAudio() {
        val spec = VideoSpec(1080, 1920, 30, 12_000_000)
        assertEquals((12_256_000 * 10.0 / 8).toLong(), VideoSpec.estimatedBytes(spec, 10.0))
    }

    @Test
    fun videoIsTheDefaultExport() {
        val options = ExportOptions()
        assertTrue(options.video)
        assertFalse(options.mixWav)
        assertTrue(ExportOptions(video = false, mixWav = false, stems = false).isEmpty)
    }
}
