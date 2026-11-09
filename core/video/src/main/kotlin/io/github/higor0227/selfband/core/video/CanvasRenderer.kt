// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video

import android.view.Surface

/**
 * Offline canvas render for export (SPEC 2.6: preview = export): draws a [CanvasScene] frame
 * by frame into a surface (an encoder's input), with the same compositor as [CanvasPreview].
 * Each frame waits for every layer's exact frame, so nothing is dropped for being slow.
 * Not thread-safe: create, use and close it on one thread.
 */
interface CanvasRenderer : AutoCloseable {
    /**
     * Decodes every layer to project position [position] (frames), draws the canvas and
     * submits it to the surface stamped with [presentationNanos]. Positions must not go back.
     */
    fun render(position: Double, presentationNanos: Long)
}

/** Creates [CanvasRenderer]s. */
interface CanvasRendererFactory {
    /** Renders [scene] into [surface], which is [width] × [height] pixels (the canvas fills it). */
    fun create(scene: CanvasScene, surface: Surface, width: Int, height: Int): CanvasRenderer
}

/**
 * A video decoder could not be started although the codec declares room for it (memory or
 * hardware limits): [opened] decoders were running. The export retries with fewer per pass.
 */
class DecoderStartException(val opened: Int, cause: Throwable) : Exception("decoder ${opened + 1} could not start", cause)
