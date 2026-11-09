// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedTemplatesTest {

    private fun video() = TakeVideo(
        file = "v.mp4", lens = CameraLens.FRONT, width = 1920, height = 1080, rotationDegrees = 270,
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

    /** A free layout: a big layer with a frame and a small rounded one on top (picture in picture). */
    private val pip = CanvasSettings(
        templateId = null,
        layers = listOf(
            Layer("t1", NormRect(0f, 0f, 1f, 1f)),
            Layer("t2", NormRect(0.6f, 0.05f, 0.35f, 0.2f), z = 1, cornerRadius = 0.2f, borderWidth = 0.01f, borderColor = 0xFFFF0000),
        ),
    )

    @Test
    fun `saving keeps the frames and looks in track order`() {
        val t = LayoutTemplates.fromProject("user-1", "  PiP  ", project(2, pip))!!
        assertEquals("PiP", t.name)
        assertEquals(listOf(NormRect(0f, 0f, 1f, 1f), NormRect(0.6f, 0.05f, 0.35f, 0.2f)), t.slots[LayoutTemplate.DEFAULT])
        assertEquals(1, t.styles!![1].z)
        assertEquals(0.2f, t.styles!![1].cornerRadius)
        assertEquals(0xFFFF0000, t.styles!![1].borderColor)
    }

    @Test
    fun `hidden layers are not saved and nothing visible saves nothing`() {
        val oneHidden = pip.copy(layers = pip.layers.map { if (it.trackId == "t1") it.copy(visible = false) else it })
        val t = LayoutTemplates.fromProject("user-1", "x", project(2, oneHidden))!!
        assertEquals(1, t.slots[LayoutTemplate.DEFAULT]!!.size)
        val none = pip.copy(layers = pip.layers.map { it.copy(visible = false) })
        assertNull(LayoutTemplates.fromProject("user-1", "x", project(2, none)))
        assertNull(LayoutTemplates.fromProject("user-1", "x", project(0)))
    }

    @Test
    fun `a built-in template can be saved too`() {
        val t = LayoutTemplates.fromProject("user-1", "two", project(2, CanvasSettings(templateId = "t2")))!!
        assertEquals(LayoutTemplates.find("t2")!!.slotsFor(AspectRatio.PORTRAIT_9_16), t.slots[LayoutTemplate.DEFAULT])
    }

    @Test
    fun `applying gives a free layout with the template's frames and looks`() {
        val template = LayoutTemplates.fromProject("user-1", "PiP", project(2, pip))!!
        val target = project(2, CanvasSettings(templateId = LayoutTemplates.AUTO))
        val canvas = LayerEditing.applyTemplate(target, template)
        assertNull(canvas.templateId)
        val t2 = canvas.layers.first { it.trackId == "t2" }
        assertEquals(NormRect(0.6f, 0.05f, 0.35f, 0.2f), t2.frame)
        assertEquals(0.2f, t2.cornerRadius)
        assertEquals(1, t2.z)
        assertTrue(t2.visible)
        // The applied layout draws the same as the original.
        val drawn = CanvasLayout.effectiveLayers(target.copy(canvas = canvas)).map { it.trackId to it.frame }
        val original = CanvasLayout.effectiveLayers(project(2, pip)).map { it.trackId to it.frame }
        assertEquals(original, drawn)
    }

    @Test
    fun `extra tracks are hidden and missing tracks leave slots empty`() {
        val template = LayoutTemplates.fromProject("user-1", "PiP", project(2, pip))!!
        val three = LayerEditing.applyTemplate(project(3), template)
        assertFalse(three.layers.first { it.trackId == "t3" }.visible)
        val one = LayerEditing.applyTemplate(project(1), template)
        assertEquals(1, one.layers.size)
        assertEquals(NormRect(0f, 0f, 1f, 1f), one.layers.single().frame)
    }

    @Test
    fun `the project does not depend on the template after applying`() {
        val template = LayoutTemplates.fromProject("user-1", "PiP", project(2, pip))!!
        val applied = project(2).let { it.copy(canvas = LayerEditing.applyTemplate(it, template)) }
        // The template id is nowhere in the project: deleting the template cannot change it.
        assertFalse(applied.canvas.toString().contains("user-1"))
    }

    @Test
    fun `saved templates round trip through the template file format`() {
        val template = LayoutTemplates.fromProject("user-1", "PiP", project(2, pip))!!
        val parsed = LayoutTemplates.parse(LayoutTemplates.encode(listOf(template)))
        assertEquals(listOf(template), parsed)
        // Built-in templates still parse (no name, no styles).
        assertTrue(LayoutTemplates.builtIn.all { it.name == null && it.styles == null })
    }
}
