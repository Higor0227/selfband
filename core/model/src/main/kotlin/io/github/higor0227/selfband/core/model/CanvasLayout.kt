// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlin.math.ceil
import kotlin.math.min

/** Layout math for the canvas (pure, unit-tested). */
object CanvasLayout {

    /** Grid of [columns] × [rows] cells. */
    data class Grid(val columns: Int, val rows: Int)

    /**
     * Chooses the grid for [count] videos of aspect [sourceAspect] (width / height) on a canvas
     * of aspect [canvasAspect]: the one that keeps the most picture after cropping each video to
     * fill its cell, counting empty cells as lost area. Ties go to stacking on portrait and
     * square canvases and to side by side on landscape ones.
     */
    fun autoGrid(count: Int, canvasAspect: Float, sourceAspect: Float): Grid {
        if (count <= 1) return Grid(1, 1)
        var best = Grid(1, count)
        var bestScore = -1f
        val order = if (canvasAspect > 1f) (count downTo 1) else (1..count)
        for (columns in order) {
            val rows = ceil(count / columns.toFloat()).toInt()
            if ((rows - 1) * columns >= count) continue // a whole row would be empty
            val cellAspect = canvasAspect * rows / columns
            val kept = min(cellAspect / sourceAspect, sourceAspect / cellAspect)
            val score = kept * count / (columns * rows)
            if (score > bestScore + 1e-4f) {
                bestScore = score
                best = Grid(columns, rows)
            }
        }
        return best
    }

    /**
     * Cells of [grid] for [count] items, row by row. A last row with fewer items is centered.
     */
    fun cells(count: Int, grid: Grid): List<NormRect> {
        val w = 1f / grid.columns
        val h = 1f / grid.rows
        return (0 until count).map { i ->
            val row = i / grid.columns
            val col = i % grid.columns
            val inRow = min(grid.columns, count - row * grid.columns)
            val inset = (grid.columns - inRow) * w / 2f
            NormRect(inset + col * w, row * h, w, h)
        }
    }

    /**
     * Centered crop of a video with aspect [sourceAspect] that fills a frame with aspect
     * [frameAspect] (both width / height, in pixels).
     */
    fun coverCrop(sourceAspect: Float, frameAspect: Float): NormRect =
        if (sourceAspect > frameAspect) {
            val w = frameAspect / sourceAspect
            NormRect((1f - w) / 2f, 0f, w, 1f)
        } else {
            val h = sourceAspect / frameAspect
            NormRect(0f, (1f - h) / 2f, 1f, h)
        }

    /**
     * Layers to draw, bottom to top: the stored layer of each track whose active take has
     * video, or an automatic grid cell (with a filling crop) for tracks without one.
     */
    fun effectiveLayers(project: Project): List<Layer> {
        val canvasAspect = project.aspectRatio.value
        val videoTracks = project.tracks.filter { it.activeTake?.video != null }
        val stored = project.canvas.layers.associateBy { it.trackId }
        val unplaced = videoTracks.filter { it.id !in stored }
        val sourceAspect = unplaced.firstNotNullOfOrNull { it.activeTake?.video?.displayAspect } ?: (9f / 16f)
        val grid = autoGrid(unplaced.size, canvasAspect, sourceAspect)
        val cells = cells(unplaced.size, grid)
        val auto = unplaced.mapIndexed { i, track ->
            val cell = cells[i]
            val video = track.activeTake!!.video!!
            Layer(
                trackId = track.id,
                frame = cell,
                crop = coverCrop(video.displayAspect, canvasAspect * cell.width / cell.height),
            )
        }.associateBy { it.trackId }
        return videoTracks.mapNotNull { stored[it.id] ?: auto[it.id] }
            .withIndex()
            .sortedWith(compareBy({ it.value.z }, { it.index }))
            .map { it.value }
    }
}

/** Width / height of the video as displayed (after rotation). */
val TakeVideo.displayAspect: Float
    get() {
        val rotated = rotationDegrees % 180 != 0
        val w = if (rotated) height else width
        val h = if (rotated) width else height
        return if (h > 0) w.toFloat() / h else 1f
    }
