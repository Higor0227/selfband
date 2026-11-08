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
     * Refits [crop] (normalized to a video of aspect [sourceAspect]) to a frame of aspect
     * [frameAspect] without distortion, keeping what the user chose: its center and its zoom
     * relative to the largest crop of its own shape. So a full (filling) crop stays full, a
     * zoomed one stays zoomed by the same factor, and nothing is lost on a format change (the
     * stored crop itself never changes). The result is clamped inside the video.
     */
    fun fitCrop(crop: NormRect, sourceAspect: Float, frameAspect: Float): NormRect {
        val cropAspect = if (crop.height > 0f) crop.width / crop.height * sourceAspect else frameAspect
        val zoom = (coverCrop(sourceAspect, cropAspect).width / crop.width.coerceAtLeast(1e-4f)).coerceAtLeast(1f)
        val max = coverCrop(sourceAspect, frameAspect)
        val w = max.width / zoom
        val h = max.height / zoom
        val cx = crop.x + crop.width / 2f
        val cy = crop.y + crop.height / 2f
        return NormRect(
            (cx - w / 2f).coerceIn(0f, 1f - w),
            (cy - h / 2f).coerceIn(0f, 1f - h),
            w,
            h,
        )
    }

    /** Slots of the layout in effect: the template's, the auto grid's, or null for free layout. */
    fun slots(project: Project, templates: List<LayoutTemplate> = LayoutTemplates.builtIn): List<NormRect>? {
        val id = project.canvas.templateId ?: return null
        val canvasAspect = project.aspectRatio.value
        templates.firstOrNull { it.id == id }?.let { return it.slotsFor(project.aspectRatio) }
        // Auto (also the fallback for an unknown id): grid shaped by the videos.
        val videos = project.tracks.mapNotNull { it.activeTake?.video }
        val sourceAspect = videos.firstOrNull()?.displayAspect ?: (9f / 16f)
        return cells(videos.size, autoGrid(videos.size, canvasAspect, sourceAspect))
    }

    /** Video tracks that the current template has no slot for (they are hidden). */
    fun hiddenByTemplate(project: Project, templates: List<LayoutTemplate> = LayoutTemplates.builtIn): Int {
        val slots = slots(project, templates) ?: return 0
        return (project.tracks.count { it.activeTake?.video != null } - slots.size).coerceAtLeast(0)
    }

    /**
     * Layers to draw, bottom to top, one per track whose active take has video. The frame comes
     * from the layout's slot (tracks in order; tracks beyond the last slot are hidden) or, in a
     * free layout, from the stored layer. Other properties come from the stored layer, and the
     * crop is refit to the frame's shape.
     */
    fun effectiveLayers(project: Project, templates: List<LayoutTemplate> = LayoutTemplates.builtIn): List<Layer> {
        val canvasAspect = project.aspectRatio.value
        val videoTracks = project.tracks.filter { it.activeTake?.video != null }
        val stored = project.canvas.layers.associateBy { it.trackId }
        val slots = slots(project, templates)
        val fallback by lazy { cells(videoTracks.size, autoGrid(videoTracks.size, canvasAspect, 9f / 16f)) }
        return videoTracks.mapIndexed { i, track ->
            val video = track.activeTake!!.video!!
            val saved = stored[track.id]
            val base = saved ?: Layer(trackId = track.id, frame = fallback[i])
            val slot = if (slots != null) slots.getOrNull(i) else base.frame
            val frame = slot ?: base.frame
            base.copy(
                frame = frame,
                crop = fitCrop(base.crop, video.displayAspect, canvasAspect * frame.width / frame.height),
                visible = base.visible && slot != null,
            )
        }
            .withIndex()
            .sortedWith(compareBy({ it.value.z }, { it.index }))
            .map { it.value }
    }

    /**
     * Track whose video fills a blurred background: the chosen one if its active take still
     * has video, else the first video track. Null when the background is not a blur or no
     * track has video (the solid color is shown then).
     */
    fun blurSource(project: Project): Track? {
        val canvas = project.canvas
        if (canvas.background != BackgroundKind.BLUR) return null
        val videoTracks = project.tracks.filter { it.activeTake?.video != null }
        return videoTracks.firstOrNull { it.id == canvas.backgroundTrackId } ?: videoTracks.firstOrNull()
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
