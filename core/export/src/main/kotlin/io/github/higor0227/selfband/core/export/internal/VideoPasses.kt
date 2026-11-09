// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export.internal

import io.github.higor0227.selfband.core.model.Layer
import io.github.higor0227.selfband.core.model.NormRect
import io.github.higor0227.selfband.core.video.CanvasScene
import io.github.higor0227.selfband.core.video.SceneLayer
import java.io.File

/**
 * Multi-pass composition (SPEC 3.5) for scenes with more videos than simultaneous decoders.
 * The layers, bottom to top, are split into groups. Pass 1 draws the background and the first
 * group into a high-quality intermediate video; each later pass draws the previous
 * intermediate over the whole canvas, then its own group; the last pass is the final file.
 * Painting bottom to top this way gives exactly the single-pass picture, without needing
 * transparency in the intermediates.
 */
internal object VideoPasses {
    /** Track id of the layer that carries the previous pass. */
    const val PASS_LAYER_ID = "selfband:pass"

    /** Intermediate bitrate, as a multiple of the final one (generation loss stays invisible). */
    const val INTERMEDIATE_BITRATE_FACTOR = 2.5

    /**
     * Layer index ranges, one per pass, so that no pass decodes more than [maxDecoders]
     * videos: the first pass also decodes the blurred background's video when it is not one of
     * its layers, every later pass decodes the previous intermediate. One range when it all
     * fits. Null when it cannot be split (fewer than 2 decoders).
     */
    fun plan(scene: CanvasScene, maxDecoders: Int): List<IntRange>? {
        if (scene.decodedLayers.size <= maxDecoders) return listOf(scene.layers.indices)
        if (maxDecoders < 2) return null
        val layers = scene.layers
        val groups = ArrayList<IntRange>()
        var start = 0
        while (start < layers.size) {
            val first = groups.isEmpty()
            val files = HashSet<String>()
            if (first) scene.blur?.let { files += it.file.absolutePath }
            val capacity = if (first) maxDecoders else maxDecoders - 1
            var end = start
            while (end < layers.size) {
                val path = layers[end].file.absolutePath
                if (path !in files && files.size + 1 > capacity) break
                files += path
                end++
            }
            if (end == start) end++ // always progress (only with a blur source and 2 decoders)
            groups += start until end
            start = end
        }
        return groups
    }

    /**
     * The scene drawn by one pass: the first keeps the background; later ones start with
     * [previous] (an intermediate of [width] × [height] at [frameRate] whose frame k shows
     * project position `start + k · sampleRate / frameRate`) covering the canvas.
     */
    fun passScene(
        scene: CanvasScene,
        group: IntRange,
        previous: File?,
        width: Int,
        height: Int,
        frameRate: Int,
        start: Long,
    ): CanvasScene {
        val layers = scene.layers.slice(group)
        if (previous == null) return scene.copy(layers = layers)
        val carrier = SceneLayer(
            file = previous,
            width = width,
            height = height,
            rotationDegrees = 0,
            frameRate = frameRate,
            startFrame = start.toDouble(),
            layer = Layer(trackId = PASS_LAYER_ID, frame = NormRect.FULL, crop = NormRect.FULL),
        )
        return scene.copy(layers = listOf(carrier) + layers, blur = null, image = null)
    }
}
