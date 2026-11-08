// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video

import android.view.Surface
import io.github.higor0227.selfband.core.model.AspectRatio
import io.github.higor0227.selfband.core.model.Layer
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
)

/** What the canvas shows (SPEC 2.6). [layers] are bottom to top. */
data class CanvasScene(
    val aspectRatio: AspectRatio,
    val sampleRate: Int,
    /** ARGB. */
    val backgroundColor: Long,
    val layers: List<SceneLayer>,
)

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

    /** While paused nothing is drawn and the decoders idle (screen not visible). */
    fun setPaused(paused: Boolean)

    /** Releases decoders, GL objects and the render thread. The instance is unusable after. */
    fun release()
}

/** Creates [CanvasPreview] instances (one per screen). */
interface CanvasPreviewFactory {
    fun create(): CanvasPreview
}
