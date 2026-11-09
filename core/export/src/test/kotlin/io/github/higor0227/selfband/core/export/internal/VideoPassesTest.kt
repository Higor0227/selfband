// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export.internal

import io.github.higor0227.selfband.core.model.AspectRatio
import io.github.higor0227.selfband.core.model.Layer
import io.github.higor0227.selfband.core.model.NormRect
import io.github.higor0227.selfband.core.video.CanvasScene
import io.github.higor0227.selfband.core.video.SceneLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoPassesTest {
    private fun layer(i: Int) = SceneLayer(
        file = File("/p/t$i.mp4"), width = 1080, height = 1920, rotationDegrees = 0, frameRate = 30,
        startFrame = 0.0, layer = Layer(trackId = "t$i", frame = NormRect.FULL),
    )

    private fun scene(n: Int, blur: SceneLayer? = null) =
        CanvasScene(AspectRatio.PORTRAIT_9_16, 48_000, 0xFF000000, (0 until n).map(::layer), blur = blur)

    @Test
    fun everythingFitsInOnePass() {
        assertEquals(listOf(0 until 8), VideoPasses.plan(scene(8), maxDecoders = 8))
    }

    @Test
    fun laterPassesKeepOneDecoderForTheIntermediate() {
        // 16 videos, 6 decoders: 6, then 5 + intermediate, then 5 + intermediate.
        assertEquals(listOf(0..5, 6..10, 11..15), VideoPasses.plan(scene(16), maxDecoders = 6))
    }

    @Test
    fun aSeparateBlurSourceTakesADecoderInTheFirstPass() {
        val blur = layer(99)
        assertEquals(listOf(0..4, 5..9, 10..11), VideoPasses.plan(scene(12, blur), maxDecoders = 6))
        // A blur source that is one of the first pass's layers costs nothing extra.
        assertEquals(listOf(0..5, 6..10, 11..11), VideoPasses.plan(scene(12, layer(2)), maxDecoders = 6))
    }

    @Test
    fun twoDecodersAreTheMinimum() {
        val plan = VideoPasses.plan(scene(4), maxDecoders = 2)!!
        assertEquals(listOf(0..1, 2..2, 3..3), plan)
        assertNull(VideoPasses.plan(scene(4), maxDecoders = 1))
    }

    @Test
    fun everyLayerIsDrawnOnceInOrder() {
        val plan = VideoPasses.plan(scene(13), maxDecoders = 4)!!
        assertEquals((0 until 13).toList(), plan.flatMap { it.toList() })
    }

    @Test
    fun laterPassesStartWithThePreviousOneOverTheCanvas() {
        val s = scene(6, blur = layer(0))
        val first = VideoPasses.passScene(s, 0..2, null, 1080, 1920, 30, start = 4800)
        assertEquals(3, first.layers.size)
        assertEquals(s.blur, first.blur)

        val prev = File("/cache/pass1.mp4")
        val second = VideoPasses.passScene(s, 3..5, prev, 1080, 1920, 30, start = 4800)
        assertEquals(4, second.layers.size)
        val carrier = second.layers.first()
        assertEquals(prev, carrier.file)
        assertEquals(NormRect.FULL, carrier.layer.frame)
        assertEquals(4800.0, carrier.startFrame, 0.0)
        assertNull(second.blur)
        assertTrue(second.layers.drop(1) == s.layers.slice(3..5))
    }
}
