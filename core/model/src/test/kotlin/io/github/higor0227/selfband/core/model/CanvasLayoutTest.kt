// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasLayoutTest {
    private val portrait = 9f / 16f
    private val landscape = 16f / 9f

    @Test
    fun autoGridPortraitCanvasPortraitVideos() {
        assertEquals(CanvasLayout.Grid(1, 1), CanvasLayout.autoGrid(1, portrait, portrait))
        assertEquals(CanvasLayout.Grid(1, 2), CanvasLayout.autoGrid(2, portrait, portrait))
        assertEquals(CanvasLayout.Grid(2, 2), CanvasLayout.autoGrid(3, portrait, portrait))
        assertEquals(CanvasLayout.Grid(2, 2), CanvasLayout.autoGrid(4, portrait, portrait))
        assertEquals(CanvasLayout.Grid(3, 3), CanvasLayout.autoGrid(9, portrait, portrait))
    }

    @Test
    fun autoGridLandscapeCanvasPrefersSideBySide() {
        assertEquals(CanvasLayout.Grid(2, 1), CanvasLayout.autoGrid(2, landscape, landscape))
        assertEquals(CanvasLayout.Grid(2, 2), CanvasLayout.autoGrid(4, landscape, landscape))
    }

    @Test
    fun cellsCenterAnIncompleteLastRow() {
        val cells = CanvasLayout.cells(3, CanvasLayout.Grid(2, 2))
        assertEquals(NormRect(0f, 0f, 0.5f, 0.5f), cells[0])
        assertEquals(NormRect(0.5f, 0f, 0.5f, 0.5f), cells[1])
        assertEquals(NormRect(0.25f, 0.5f, 0.5f, 0.5f), cells[2])
    }

    @Test
    fun coverCropKeepsTheCenter() {
        // 16:9 video in a square frame: keep the middle 9/16 of the width.
        val c = CanvasLayout.coverCrop(16f / 9f, 1f)
        assertEquals(0.5625f, c.width, 1e-5f)
        assertEquals(1f, c.height, 1e-5f)
        assertEquals((1f - 0.5625f) / 2f, c.x, 1e-5f)
        // 9:16 video in a 9:8 frame (half of a 9:16 canvas): keep the middle half of the height.
        val d = CanvasLayout.coverCrop(9f / 16f, 9f / 8f)
        assertEquals(1f, d.width, 1e-5f)
        assertEquals(0.5f, d.height, 1e-5f)
        assertEquals(0.25f, d.y, 1e-5f)
    }

    @Test
    fun displayAspectAppliesRotation() {
        val v = TakeVideo(
            file = "a.mp4", lens = CameraLens.FRONT, width = 1920, height = 1080, rotationDegrees = 270,
            previewMirrored = true, frameRate = 30, frameCount = 1, durationUs = 0,
            startPositionFrames = 0.0, clock = VideoClock.MONOTONIC,
        )
        assertEquals(1080f / 1920f, v.displayAspect, 1e-5f)
        assertEquals(1920f / 1080f, v.copy(rotationDegrees = 0).displayAspect, 1e-5f)
    }

    private fun video(rotation: Int = 270) = TakeVideo(
        file = "v.mp4", lens = CameraLens.FRONT, width = 1920, height = 1080, rotationDegrees = rotation,
        previewMirrored = true, frameRate = 30, frameCount = 1, durationUs = 0,
        startPositionFrames = 0.0, clock = VideoClock.MONOTONIC,
    )

    private fun project(tracks: Int, canvas: CanvasSettings = CanvasSettings(), aspect: AspectRatio = AspectRatio.PORTRAIT_9_16) =
        Project(
            id = "p", name = "p", createdAtMillis = 0, updatedAtMillis = 0, sampleRate = 48_000,
            aspectRatio = aspect, canvas = canvas,
            tracks = (1..tracks).map { n ->
                Track(
                    id = "t$n", name = "#$n", activeTakeId = "k$n",
                    takes = listOf(
                        Take(
                            id = "k$n", audioFile = "k$n.wav", channelCount = 1, lengthFrames = 48_000,
                            recordStartFrame = 0, autoOffsetFrames = 0,
                            alignment = AlignmentInfo(AlignmentMethod.TIMESTAMPS, 0.0), createdAtMillis = 0,
                            video = video(),
                        ),
                    ),
                )
            },
        )

    private fun assertRect(expected: NormRect, actual: NormRect) {
        assertEquals(expected.x, actual.x, 1e-4f)
        assertEquals(expected.y, actual.y, 1e-4f)
        assertEquals(expected.width, actual.width, 1e-4f)
        assertEquals(expected.height, actual.height, 1e-4f)
    }

    @Test
    fun builtInTemplatesAreValid() {
        val templates = LayoutTemplates.builtIn
        assertEquals(listOf("t1", "t2", "t3", "t4", "t6", "t9"), templates.map { it.id })
        val expectedCounts = listOf(1, 2, 3, 4, 6, 9)
        for ((template, count) in templates.zip(expectedCounts)) {
            for (aspect in AspectRatio.entries) {
                val slots = template.slotsFor(aspect)
                assertEquals("${template.id} $aspect", count, slots.size)
                var area = 0f
                for (r in slots) {
                    assertTrue("${template.id} $r", r.x >= 0f && r.y >= 0f && r.right <= 1.0001f && r.bottom <= 1.0001f)
                    assertTrue(r.width > 0f && r.height > 0f)
                    area += r.width * r.height
                }
                // These templates tile the whole canvas.
                assertEquals("${template.id} $aspect", 1f, area, 1e-3f)
            }
        }
    }

    @Test
    fun fitCropKeepsAFullCropFull() {
        val src = portrait
        val full = CanvasLayout.coverCrop(src, 1f)
        assertRect(CanvasLayout.coverCrop(src, 2f), CanvasLayout.fitCrop(full, src, 2f))
        assertRect(CanvasLayout.coverCrop(src, 0.5f), CanvasLayout.fitCrop(NormRect.FULL, src, 0.5f))
    }

    @Test
    fun fitCropKeepsZoomAndCenter() {
        val src = 1f
        // Zoom 2 on the top-left quarter of a square video, refit to a 2:1 frame.
        val crop = NormRect(0.1f, 0.1f, 0.5f, 0.5f)
        val fit = CanvasLayout.fitCrop(crop, src, 2f)
        assertEquals(0.5f, fit.width, 1e-4f) // max for 2:1 is 1 × 0.5, halved by zoom 2
        assertEquals(0.25f, fit.height, 1e-4f)
        assertEquals(0.35f, fit.x + fit.width / 2, 1e-4f)
        assertEquals(0.35f, fit.y + fit.height / 2, 1e-4f)
        // Near an edge it is clamped inside the video.
        val edge = CanvasLayout.fitCrop(NormRect(0.7f, 0f, 0.3f, 0.3f), src, 0.5f)
        assertTrue(edge.right <= 1.0001f && edge.x >= 0f)
    }

    @Test
    fun templateHidesTracksWithoutASlot() {
        val p = project(3, CanvasSettings(templateId = "t2"))
        val layers = CanvasLayout.effectiveLayers(p)
        assertEquals(3, layers.size)
        assertTrue(layers[0].visible && layers[1].visible)
        assertFalse(layers[2].visible)
        assertEquals(1, CanvasLayout.hiddenByTemplate(p))
        assertRect(NormRect(0f, 0.5f, 1f, 0.5f), layers[1].frame)
    }

    @Test
    fun formatChangeUsesTheTemplateVariantAndKeepsCrops() {
        val zoomed = Layer(trackId = "t1", frame = NormRect.FULL, crop = NormRect(0.2f, 0.3f, 0.4f, 0.4f), opacity = 0.5f)
        val canvas = CanvasSettings(templateId = "t2", layers = listOf(zoomed))
        val portraitLayers = CanvasLayout.effectiveLayers(project(2, canvas))
        val landscapeLayers = CanvasLayout.effectiveLayers(project(2, canvas, AspectRatio.LANDSCAPE_16_9))
        assertRect(NormRect(0f, 0f, 1f, 0.5f), portraitLayers[0].frame)
        assertRect(NormRect(0f, 0f, 0.5f, 1f), landscapeLayers[0].frame)
        // Other properties survive; the crop keeps its center in both formats.
        assertEquals(0.5f, landscapeLayers[0].opacity)
        for (l in listOf(portraitLayers[0], landscapeLayers[0])) {
            assertEquals(0.4f, l.crop.x + l.crop.width / 2, 1e-3f)
            assertEquals(0.5f, l.crop.y + l.crop.height / 2, 1e-3f)
        }
        // Crop shape matches the frame shape in pixels (no distortion).
        for ((l, aspect) in listOf(portraitLayers[0] to AspectRatio.PORTRAIT_9_16, landscapeLayers[0] to AspectRatio.LANDSCAPE_16_9)) {
            val frameAspect = aspect.value * l.frame.width / l.frame.height
            assertEquals(frameAspect, l.crop.width / l.crop.height * video().displayAspect, 1e-3f)
        }
    }

    @Test
    fun autoIsTheDefaultAndUnknownTemplatesFallBackToIt() {
        val auto = CanvasLayout.effectiveLayers(project(4))
        val unknown = CanvasLayout.effectiveLayers(project(4, CanvasSettings(templateId = "gone")))
        assertEquals(auto, unknown)
        assertRect(NormRect(0.5f, 0.5f, 0.5f, 0.5f), auto[3].frame)
    }

    @Test
    fun freeLayoutSurvivesSerialization() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val canvas = CanvasSettings(templateId = null)
        val back = json.decodeFromString(CanvasSettings.serializer(), json.encodeToString(CanvasSettings.serializer(), canvas))
        assertNull(back.templateId)
    }

    @Test
    fun blurSourceFallsBackToTheFirstVideoTrack() {
        val chosen = project(3, CanvasSettings(background = BackgroundKind.BLUR, backgroundTrackId = "t2"))
        assertEquals("t2", CanvasLayout.blurSource(chosen)?.id)
        // Unknown (deleted) track or none chosen: the first video track.
        val gone = chosen.copy(canvas = chosen.canvas.copy(backgroundTrackId = "nope"))
        assertEquals("t1", CanvasLayout.blurSource(gone)?.id)
        // The chosen track lost its video (an audio-only take is active): the first video track.
        val audioOnly = chosen.copy(
            tracks = chosen.tracks.map { t ->
                if (t.id == "t2") t.copy(takes = t.takes.map { it.copy(video = null) }) else t
            },
        )
        assertEquals("t1", CanvasLayout.blurSource(audioOnly)?.id)
        // Not a blur background, or no video at all: nothing.
        assertNull(CanvasLayout.blurSource(project(2)))
        assertNull(CanvasLayout.blurSource(project(0, CanvasSettings(background = BackgroundKind.BLUR))))
    }

    @Test
    fun oldProjectsReadWithAColorBackground() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"backgroundColor":4278190080,"templateId":"auto","layers":[]}"""
        val canvas = json.decodeFromString(CanvasSettings.serializer(), old)
        assertEquals(BackgroundKind.COLOR, canvas.background)
        assertNull(canvas.backgroundImage)
        val image = CanvasSettings(background = BackgroundKind.IMAGE, backgroundImage = "bg.jpg")
        assertEquals(image, json.decodeFromString(CanvasSettings.serializer(), json.encodeToString(CanvasSettings.serializer(), image)))
    }
}
