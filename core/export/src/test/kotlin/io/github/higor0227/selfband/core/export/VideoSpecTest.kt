// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export

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
    fun videoIsTheDefaultExport() {
        val options = ExportOptions()
        assertTrue(options.video)
        assertFalse(options.mixWav)
        assertTrue(ExportOptions(video = false, mixWav = false, stems = false).isEmpty)
    }
}
