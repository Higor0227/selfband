// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video

import android.view.Surface
import io.github.higor0227.selfband.core.model.AspectRatio
import io.github.higor0227.selfband.core.model.Layer
import io.github.higor0227.selfband.core.model.ProxyMode
import java.io.File

/** One layer to draw: its video file, where it sits on the timeline and its placement. */
data class SceneLayer(
    val file: File,
    /** Encoded size, before rotation. */
    val width: Int,
    val height: Int,
    /** Clockwise rotation to apply for display. */
    val rotationDegrees: Int,
    val frameRate: Int,
    /** Project position (frames) of the first video frame. */
    val startFrame: Double,
    val layer: Layer,
    /** Light copy of [file] for the preview (SPEC 3.5), when it has been made; same timing. */
    val proxy: File? = null,
)

/**
 * What the canvas shows (SPEC 2.6). [layers] are bottom to top. The background is
 * [backgroundColor], covered by [blur] (a video drawn over the whole canvas and blurred) or
 * [image] (a picture covering the canvas) when set.
 */
data class CanvasScene(
    val aspectRatio: AspectRatio,
    val sampleRate: Int,
    /** ARGB. */
    val backgroundColor: Long,
    val layers: List<SceneLayer>,
    /** Its layer's crop covers the canvas; frame, corners, border and opacity are ignored. */
    val blur: SceneLayer? = null,
    val image: File? = null,
) {
    /** Videos to decode, one decoder each: the drawn layers, then the blur source unless it is one of them. */
    val decodedLayers: List<SceneLayer>
        get() {
            val byFile = LinkedHashMap(layers.associateBy { it.file.absolutePath })
            blur?.let { byFile.putIfAbsent(it.file.absolutePath, it) }
            return byFile.values.toList()
        }

    /** The same scene decoding each video's proxy where there is one. */
    fun withProxies(): CanvasScene {
        fun SceneLayer.light() = proxy?.let { copy(file = it, proxy = null) } ?: this
        return copy(layers = layers.map { it.light() }, blur = blur?.light())
    }
}

/** Project position (frames) shown at CLOCK_MONOTONIC time `timeNanos`. */
fun interface PositionSource {
    fun positionAt(timeNanos: Long): Double
}

/**
 * Live canvas preview (SPEC 3.5): decodes the layers' videos and composites them with
 * OpenGL ES 3 into a surface, showing for each layer the frame nearest the position given by
 * [PositionSource] (the audio clock) and dropping late frames instead of waiting for them.
 * Owns a render thread; all methods are safe to call from the main thread.
 */
interface CanvasPreview {
    /** Starts drawing into [surface] (e.g. a TextureView's), sized [width] × [height] pixels. */
    fun attach(surface: Surface, width: Int, height: Int)

    fun resize(width: Int, height: Int)

    /** Stops drawing into the current surface (before it is destroyed). Blocks briefly. */
    fun detach()

    fun setScene(scene: CanvasScene)

    fun setPosition(source: PositionSource)

    /**
     * When to decode the layers' proxies instead of the originals (SPEC 3.5). In
     * [ProxyMode.AUTO] this also happens when the originals need more decoders than the device
     * has, or when the preview measures that it cannot decode them in time.
     */
    fun setProxyMode(mode: ProxyMode)

    /** While paused nothing is drawn and the decoders are released (screen not visible). */
    fun setPaused(paused: Boolean)

    /** Releases decoders, GL objects and the render thread. The instance is unusable after. */
    fun release()
}

/** Creates [CanvasPreview] instances (one per screen). */
interface CanvasPreviewFactory {
    fun create(): CanvasPreview
}
