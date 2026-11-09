// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.export

import io.github.higor0227.selfband.core.model.VideoCodec.H264
import io.github.higor0227.selfband.core.model.VideoCodec.HEVC
import io.github.higor0227.selfband.core.export.VideoOptions
import io.github.higor0227.selfband.core.export.VideoResolution.P1080
import io.github.higor0227.selfband.core.export.VideoResolution.P2160
import io.github.higor0227.selfband.core.export.VideoResolution.P720
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFitTest {
    private val range = 1_000_000..40_000_000

    /** A device with H.264 up to 1080p60 and 4K30, and HEVC up to 1080p30. */
    private val modes = mapOf(
        VideoMode(H264, P720, 30) to range, VideoMode(H264, P720, 60) to range,
        VideoMode(H264, P1080, 30) to range, VideoMode(H264, P1080, 60) to range,
        VideoMode(H264, P2160, 30) to range,
        VideoMode(HEVC, P720, 30) to range, VideoMode(HEVC, P1080, 30) to range,
    )

    private fun fit(o: VideoOptions) = ExportViewModel.fit(o, modes)

    @Test
    fun supportedChoicesAreKept() {
        val o = VideoOptions(H264, P1080, 60)
        assertEquals(o, fit(o))
    }

    @Test
    fun theValueJustChosenIsKept() {
        // At 4K30, choosing 60 fps keeps 60 fps and lowers the size...
        assertEquals(
            VideoOptions(H264, P1080, 60),
            ExportViewModel.fit(VideoOptions(H264, P2160, 60), modes) { it.frameRate == 60 },
        )
        // ...while at 1080p60, choosing 4K keeps 4K and lowers the frame rate.
        assertEquals(
            VideoOptions(H264, P2160, 30),
            ExportViewModel.fit(VideoOptions(H264, P2160, 60), modes) { it.resolution == P2160 },
        )
    }

    @Test
    fun unsupportedSizeFallsBackToTheNearestSmallerOne() {
        assertEquals(VideoOptions(HEVC, P1080, 30), fit(VideoOptions(HEVC, P2160, 30)))
    }

    @Test
    fun manualBitrateStaysInTheEncoderRange() {
        assertEquals(40_000_000, fit(VideoOptions(H264, P1080, 30, bitRate = 90_000_000)).bitRate)
        assertEquals(null, fit(VideoOptions(H264, P1080, 30)).bitRate)
    }

    @Test
    fun unknownCapabilitiesChangeNothing() {
        val o = VideoOptions(HEVC, P2160, 60)
        assertEquals(o, ExportViewModel.fit(o, null))
    }
}
