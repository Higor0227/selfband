// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import org.junit.Assert.assertEquals
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
}
