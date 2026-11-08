// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import io.github.higor0227.selfband.core.model.LayerEditing.Edge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerEditingTest {
    private val canvasAspect = 9f / 16f
    private val sourceAspect = 9f / 16f

    /** Pixel aspect of the frame on the canvas vs the crop in the video: must match. */
    private fun assertUndistorted(l: Layer) {
        val frame = canvasAspect * l.frame.width / l.frame.height
        val crop = sourceAspect * l.crop.width / l.crop.height
        assertEquals(frame, crop, 1e-4f)
    }

    private val half = Layer(
        trackId = "a",
        frame = NormRect(0f, 0f, 1f, 0.5f),
        crop = CanvasLayout.coverCrop(sourceAspect, canvasAspect * 2f),
    )

    @Test
    fun cropEdgeShrinksTheWindowWithoutMovingTheVideo() {
        val cropped = LayerEditing.cropEdge(half, Edge.RIGHT, -0.25f)
        assertEquals(0.75f, cropped.frame.width, 1e-5f)
        assertEquals(half.crop.width * 0.75f, cropped.crop.width, 1e-5f)
        assertEquals(half.crop.x, cropped.crop.x, 1e-6f)
        assertUndistorted(cropped)
        // The left edge: the visible content's position on the canvas stays where it was.
        val left = LayerEditing.cropEdge(half, Edge.LEFT, 0.2f)
        assertEquals(0.2f, left.frame.x, 1e-5f)
        val videoLeftBefore = half.frame.x - half.crop.x * half.frame.width / half.crop.width
        val videoLeftAfter = left.frame.x - left.crop.x * left.frame.width / left.crop.width
        assertEquals(videoLeftBefore, videoLeftAfter, 1e-5f)
        assertUndistorted(left)
    }

    @Test
    fun cropEdgeCannotGoPastTheVideo() {
        // Full width already: growing to the right is impossible.
        val grown = LayerEditing.cropEdge(half, Edge.RIGHT, 0.3f)
        assertEquals(half.frame.width, grown.frame.width, 1e-5f)
        // The top can grow until the crop reaches the top of the video.
        val up = LayerEditing.cropEdge(half, Edge.TOP, -1f)
        assertEquals(0f, up.crop.y, 1e-5f)
        assertUndistorted(up)
    }

    @Test
    fun zoomAndPanStayInsideTheVideo() {
        val zoomed = LayerEditing.zoomCrop(half, 2f)
        assertEquals(half.crop.width / 2f, zoomed.crop.width, 1e-5f)
        assertEquals(half.crop.centerX, zoomed.crop.centerX, 1e-5f)
        assertUndistorted(zoomed)
        val panned = LayerEditing.panCrop(zoomed, 10f, 10f) // far right/down drag
        assertEquals(0f, panned.crop.x, 1e-5f)
        assertEquals(0f, panned.crop.y, 1e-5f)
        // Zooming out never goes past the whole video.
        val out = LayerEditing.zoomCrop(half, 0.1f)
        assertTrue(out.crop.width <= 1.0001f && out.crop.height <= 1.0001f)
        assertUndistorted(out)
    }

    @Test
    fun scaleKeepsShapeAndPivot() {
        val l = LayerEditing.scale(half.copy(frame = NormRect(0.2f, 0.2f, 0.4f, 0.2f)), 0.5f)
        assertEquals(0.2f, l.frame.width, 1e-5f)
        assertEquals(0.1f, l.frame.height, 1e-5f)
        assertEquals(0.4f, l.frame.centerX, 1e-5f)
        assertEquals(0.3f, l.frame.centerY, 1e-5f)
        val tiny = LayerEditing.scale(l, 0.0001f)
        assertEquals(LayerEditing.MIN_SIZE, minOf(tiny.frame.width, tiny.frame.height), 1e-5f)
    }

    @Test
    fun alignAndRestack() {
        val l = half.copy(frame = NormRect(0.3f, 0.3f, 0.4f, 0.2f))
        assertEquals(0.6f, LayerEditing.align(l, LayerEditing.Align.RIGHT).frame.x, 1e-5f)
        assertEquals(0.4f, LayerEditing.align(l, LayerEditing.Align.CENTER_V).frame.y, 1e-5f)
        val canvas = CanvasSettings(
            templateId = null,
            layers = listOf(l.copy(trackId = "a"), l.copy(trackId = "b"), l.copy(trackId = "c")),
        )
        val up = LayerEditing.restack(canvas, "a", up = true, trackOrder = listOf("a", "b", "c"))
        assertEquals(listOf(1, 0, 2), up.layers.map { it.z })
        // Already on top: no change.
        val same = LayerEditing.restack(up, "c", up = true, trackOrder = listOf("a", "b", "c"))
        assertEquals(up, same)
    }

    @Test
    fun snapPrefersGuidesThenGrid() {
        // Center of a 0.3-wide frame proposed at x = 0.36 → snaps to the canvas center (0.35).
        val s = LayerEditing.snap(0.36f, 0.5f, 0.3f, 0.2f, emptyList(), 0.02f, 0.02f)
        assertEquals(0.35f, s.x, 1e-5f)
        assertEquals(LayerEditing.Guide(true, 0.5f), s.guides.first { it.vertical })
        // Another layer's right edge.
        val other = NormRect(0f, 0f, 0.42f, 0.1f)
        val t = LayerEditing.snap(0.43f, 0.7f, 0.2f, 0.2f, listOf(other), 0.02f, 0.02f)
        assertEquals(0.42f, t.x, 1e-5f)
        // Nothing in reach of a guide: the grid (1/12) applies, without a guide line.
        val g = LayerEditing.snap(0.170f, 0.6f, 0.05f, 0.05f, emptyList(), 0.01f, 0.01f)
        assertEquals(1f / 6f, g.x, 1e-5f)
        assertNull(g.guides.firstOrNull { it.vertical })
    }

    @Test
    fun materializeFreezesTheCurrentLayout() {
        val video = TakeVideo(
            file = "v.mp4", lens = CameraLens.FRONT, width = 1920, height = 1080, rotationDegrees = 270,
            previewMirrored = true, frameRate = 30, frameCount = 1, durationUs = 0,
            startPositionFrames = 0.0, clock = VideoClock.MONOTONIC,
        )
        val tracks = (1..2).map { n ->
            Track(
                id = "t$n", name = "#$n", activeTakeId = "k$n",
                takes = listOf(
                    Take(
                        id = "k$n", audioFile = "k$n.wav", channelCount = 1, lengthFrames = 1,
                        recordStartFrame = 0, autoOffsetFrames = 0,
                        alignment = AlignmentInfo(AlignmentMethod.TIMESTAMPS, 0.0), createdAtMillis = 0, video = video,
                    ),
                ),
            )
        }
        val p = Project(id = "p", name = "p", createdAtMillis = 0, updatedAtMillis = 0, sampleRate = 48_000, tracks = tracks)
        val canvas = LayerEditing.materialize(p)
        assertNull(canvas.templateId)
        assertEquals(CanvasLayout.effectiveLayers(p), CanvasLayout.effectiveLayers(p.copy(canvas = canvas)))
    }
}
