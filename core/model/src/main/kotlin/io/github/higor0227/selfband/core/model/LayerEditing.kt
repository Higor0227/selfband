// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure editing operations on canvas layers (SPEC 2.6), used by the layout editor. Frames are
 * normalized to the canvas, crops to the displayed video. Every operation keeps the crop's
 * pixel shape equal to the frame's, so the video is never distorted.
 */
object LayerEditing {
    /** Smallest layer side, as a fraction of the canvas. */
    const val MIN_SIZE = 0.05f
    /** Largest layer side, as a multiple of the canvas (zooming past the edges is allowed). */
    const val MAX_SIZE = 3f
    /** Smallest crop side, as a fraction of the video. */
    const val MIN_CROP = 0.05f

    enum class Edge { LEFT, TOP, RIGHT, BOTTOM }

    enum class Align { LEFT, CENTER_H, RIGHT, TOP, CENTER_V, BOTTOM }

    /**
     * Turns the current layout into a free one: every video track gets a stored layer with the
     * frame and crop it currently shows, and the template is dropped. Editing starts from here.
     */
    fun materialize(project: Project, templates: List<LayoutTemplate> = LayoutTemplates.builtIn): CanvasSettings =
        project.canvas.copy(templateId = null, layers = CanvasLayout.effectiveLayers(project, templates))

    fun replace(canvas: CanvasSettings, layer: Layer): CanvasSettings =
        canvas.copy(layers = canvas.layers.map { if (it.trackId == layer.trackId) layer else it })

    /** Moves the frame so its center stays on the canvas. The crop is unchanged. */
    fun moveTo(layer: Layer, x: Float, y: Float): Layer {
        val f = layer.frame
        val nx = x.coerceIn(-f.width / 2f, 1f - f.width / 2f)
        val ny = y.coerceIn(-f.height / 2f, 1f - f.height / 2f)
        return layer.copy(frame = f.copy(x = nx, y = ny))
    }

    /** Scales the frame around [pivotX], [pivotY] (canvas coordinates), keeping its shape. */
    fun scale(layer: Layer, factor: Float, pivotX: Float = layer.frame.centerX, pivotY: Float = layer.frame.centerY): Layer {
        val f = layer.frame
        val minFactor = MIN_SIZE / min(f.width, f.height)
        val maxFactor = MAX_SIZE / max(f.width, f.height)
        val k = factor.coerceIn(minFactor, maxFactor)
        val w = f.width * k
        val h = f.height * k
        val x = pivotX - (pivotX - f.x) * k
        val y = pivotY - (pivotY - f.y) * k
        return moveTo(layer.copy(frame = NormRect(f.x, f.y, w, h)), x, y)
    }

    /**
     * Crop handle: moves [edge] by [delta] (canvas units; positive = right/down). The window
     * onto the video grows or shrinks; the video itself does not move or scale on screen.
     */
    fun cropEdge(layer: Layer, edge: Edge, delta: Float): Layer {
        val f = layer.frame
        val c = layer.crop
        // Video units per canvas unit, per axis (constant while cropping).
        val kx = c.width / f.width
        val ky = c.height / f.height
        return when (edge) {
            Edge.RIGHT -> {
                val d = delta.coerceIn((MIN_CROP - c.width) / kx, (1f - c.right) / kx)
                    .coerceAtLeast(MIN_SIZE - f.width)
                layer.copy(frame = f.copy(width = f.width + d), crop = c.copy(width = c.width + d * kx))
            }
            Edge.LEFT -> {
                val d = delta.coerceIn(-c.x / kx, (c.width - MIN_CROP) / kx).coerceAtMost(f.width - MIN_SIZE)
                layer.copy(
                    frame = f.copy(x = f.x + d, width = f.width - d),
                    crop = c.copy(x = c.x + d * kx, width = c.width - d * kx),
                )
            }
            Edge.BOTTOM -> {
                val d = delta.coerceIn((MIN_CROP - c.height) / ky, (1f - c.bottom) / ky)
                    .coerceAtLeast(MIN_SIZE - f.height)
                layer.copy(frame = f.copy(height = f.height + d), crop = c.copy(height = c.height + d * ky))
            }
            Edge.TOP -> {
                val d = delta.coerceIn(-c.y / ky, (c.height - MIN_CROP) / ky).coerceAtMost(f.height - MIN_SIZE)
                layer.copy(
                    frame = f.copy(y = f.y + d, height = f.height - d),
                    crop = c.copy(y = c.y + d * ky, height = c.height - d * ky),
                )
            }
        }
    }

    /** Pans the video inside the frame by a drag of ([dx], [dy]) canvas units. */
    fun panCrop(layer: Layer, dx: Float, dy: Float): Layer {
        val f = layer.frame
        val c = layer.crop
        val x = (c.x - dx * c.width / f.width).coerceIn(0f, 1f - c.width)
        val y = (c.y - dy * c.height / f.height).coerceIn(0f, 1f - c.height)
        return layer.copy(crop = c.copy(x = x, y = y))
    }

    /**
     * Zooms the video inside the frame ([factor] > 1 = closer), around the crop's center. It
     * cannot zoom out past the whole video nor in past [MIN_CROP].
     */
    fun zoomCrop(layer: Layer, factor: Float): Layer {
        val c = layer.crop
        val maxScale = min(1f / c.width, 1f / c.height)
        val minScale = MIN_CROP / min(c.width, c.height)
        val s = (1f / factor).coerceIn(minScale, maxScale)
        val w = c.width * s
        val h = c.height * s
        val x = (c.centerX - w / 2f).coerceIn(0f, 1f - w)
        val y = (c.centerY - h / 2f).coerceIn(0f, 1f - h)
        return layer.copy(crop = NormRect(x, y, w, h))
    }

    /** Largest crop of the frame's shape, centered: undoes cropping and zoom. */
    fun resetCrop(layer: Layer, sourceAspect: Float, canvasAspect: Float): Layer =
        layer.copy(crop = CanvasLayout.coverCrop(sourceAspect, canvasAspect * layer.frame.width / layer.frame.height))

    fun align(layer: Layer, align: Align): Layer {
        val f = layer.frame
        return when (align) {
            Align.LEFT -> moveTo(layer, 0f, f.y)
            Align.CENTER_H -> moveTo(layer, (1f - f.width) / 2f, f.y)
            Align.RIGHT -> moveTo(layer, 1f - f.width, f.y)
            Align.TOP -> moveTo(layer, f.x, 0f)
            Align.CENTER_V -> moveTo(layer, f.x, (1f - f.height) / 2f)
            Align.BOTTOM -> moveTo(layer, f.x, 1f - f.height)
        }
    }

    /**
     * Moves the layer one step up ([up]) or down in the drawing order and renumbers z as
     * 0..n-1 in the new order (ties keep track order).
     */
    fun restack(canvas: CanvasSettings, trackId: String, up: Boolean, trackOrder: List<String>): CanvasSettings {
        val ordered = canvas.layers
            .sortedWith(compareBy({ it.z }, { trackOrder.indexOf(it.trackId) }))
            .toMutableList()
        val i = ordered.indexOfFirst { it.trackId == trackId }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in ordered.indices) return canvas
        ordered[i] = ordered[j].also { ordered[j] = ordered[i] }
        val z = ordered.withIndex().associate { it.value.trackId to it.index }
        return canvas.copy(layers = canvas.layers.map { it.copy(z = z.getValue(it.trackId)) })
    }

    /** A guide line the moving layer snapped to: vertical (x) or horizontal (y), canvas units. */
    data class Guide(val vertical: Boolean, val position: Float)

    data class SnapResult(val x: Float, val y: Float, val guides: List<Guide>)

    /**
     * Snaps a frame of size [w] × [h] proposed at ([x], [y]): its left/center/right and
     * top/center/bottom go to the nearest target within [threshold] (canvas units). Targets are
     * the canvas edges and center, the other layers' edges and centers, then the grid lines
     * (multiples of 1 / [gridDivisions]), which only apply if no guide is in reach.
     */
    fun snap(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        others: List<NormRect>,
        thresholdX: Float,
        thresholdY: Float,
        gridDivisions: Int = 12,
    ): SnapResult {
        val guidesX = mutableListOf(0f, 0.5f, 1f)
        val guidesY = mutableListOf(0f, 0.5f, 1f)
        for (o in others) {
            guidesX += listOf(o.x, o.centerX, o.right)
            guidesY += listOf(o.y, o.centerY, o.bottom)
        }
        val grid = (0..gridDivisions).map { it / gridDivisions.toFloat() }
        val (sx, gx) = snapAxis(x, w, guidesX, grid, thresholdX)
        val (sy, gy) = snapAxis(y, h, guidesY, grid, thresholdY)
        return SnapResult(sx, sy, listOfNotNull(gx?.let { Guide(true, it) }, gy?.let { Guide(false, it) }))
    }

    /** Returns the snapped start and the guide line used (null for grid or no snap). */
    private fun snapAxis(start: Float, size: Float, guides: List<Float>, grid: List<Float>, threshold: Float): Pair<Float, Float?> {
        val anchors = floatArrayOf(0f, size / 2f, size)
        var best: Float? = null
        var bestLine = 0f
        for (line in guides) for (a in anchors) {
            val d = line - (start + a)
            if (abs(d) <= threshold && (best == null || abs(d) < abs(best))) {
                best = d
                bestLine = line
            }
        }
        if (best != null) return (start + best) to bestLine
        var gridBest: Float? = null
        for (line in grid) for (a in anchors) {
            val d = line - (start + a)
            if (abs(d) <= threshold && (gridBest == null || abs(d) < abs(gridBest))) gridBest = d
        }
        return (start + (gridBest ?: 0f)) to null
    }
}

val NormRect.centerX: Float get() = x + width / 2f
val NormRect.centerY: Float get() = y + height / 2f
