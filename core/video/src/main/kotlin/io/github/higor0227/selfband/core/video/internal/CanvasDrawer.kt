// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.graphics.SurfaceTexture
import android.opengl.GLES30
import android.os.Handler
import android.util.Log
import android.view.Surface
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.value
import io.github.higor0227.selfband.core.video.CanvasScene
import io.github.higor0227.selfband.core.video.SceneLayer
import kotlin.math.min

/**
 * One video's texture: decoders render into [surface], the GL thread latches frames with
 * [latch]. Create, use and release on the GL thread.
 */
internal open class LayerTexture(
    val key: String,
    var scene: SceneLayer,
    /** False for the video that only feeds the blurred background. */
    var drawn: Boolean,
) {
    val texture = LayerProgram.createExternalTexture()
    val surfaceTexture = SurfaceTexture(texture)
    val surface = Surface(surfaceTexture)
    var hasFrame = false
    val texMatrix = FloatArray(16)

    /** Takes the newest frame rendered into [surface]; the GL context must be current. */
    fun latch(): Boolean = try {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        hasFrame = true
        true
    } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "canvas: updateTexImage failed", e)
        false
    }

    open fun release() {
        surface.release()
        surfaceTexture.release()
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

/**
 * Draws a [CanvasScene] (SPEC 2.6, 3.5): background color, blurred video or picture, then the
 * layers bottom to top. The one drawing path shared by the live preview and the export, so
 * the exported file looks exactly like the preview. GL thread only; [handler] is its handler.
 */
internal class CanvasDrawer(handler: Handler) {
    private val program = LayerProgram()
    val background = BackgroundRenderer(handler)
    private val params = LayerProgram.Params()
    private val canvasFrame = FloatArray(4)

    /**
     * Draws [scene] with the frames latched in [layers] into the current surface of
     * [width] × [height] pixels, the canvas fitted (letterboxed) into it.
     */
    fun draw(scene: CanvasScene, layers: List<LayerTexture>, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (width <= 0 || height <= 0) return

        // Canvas rectangle: fit the project aspect ratio into the surface.
        val aspect = scene.aspectRatio.value
        val cw: Float
        val ch: Float
        if (width.toFloat() / height > aspect) {
            ch = height.toFloat(); cw = ch * aspect
        } else {
            cw = width.toFloat(); ch = cw / aspect
        }
        val cx = (width - cw) / 2f
        val cy = (height - ch) / 2f

        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(cx.toInt(), (height - cy - ch).toInt(), cw.toInt(), ch.toInt())
        val bg = scene.backgroundColor
        GLES30.glClearColor(
            ((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f, 1f,
        )
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        canvasFrame[0] = cx / width
        canvasFrame[1] = cy / height
        canvasFrame[2] = cw / width
        canvasFrame[3] = ch / height
        drawBackground(scene, layers, cw, ch, aspect, width, height)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        val shorter = min(cw, ch)
        for (i in layers.indices) {
            val state = layers[i]
            val layer = state.scene.layer
            if (!state.drawn || !layer.visible || layer.opacity <= 0f) continue
            val p = params
            val f = layer.frame
            p.frame[0] = (cx + f.x * cw) / width
            p.frame[1] = (cy + f.y * ch) / height
            p.frame[2] = f.width * cw / width
            p.frame[3] = f.height * ch / height
            p.widthPx = f.width * cw
            p.heightPx = f.height * ch
            p.crop[0] = layer.crop.x
            p.crop[1] = layer.crop.y
            p.crop[2] = layer.crop.width
            p.crop[3] = layer.crop.height
            p.quarterTurns = ((state.scene.rotationDegrees / 90) % 4 + 4) % 4
            p.radiusPx = layer.cornerRadius * min(p.widthPx, p.heightPx)
            p.borderPx = layer.borderWidth * shorter
            val bc = layer.borderColor
            p.borderColor[0] = ((bc shr 16) and 0xFF) / 255f
            p.borderColor[1] = ((bc shr 8) and 0xFF) / 255f
            p.borderColor[2] = (bc and 0xFF) / 255f
            p.borderColor[3] = ((bc shr 24) and 0xFF) / 255f
            p.opacity = layer.opacity
            p.hasTexture = state.hasFrame
            if (state.hasFrame) {
                System.arraycopy(state.texMatrix, 0, p.texMatrix, 0, 16)
                p.texture = state.texture
            }
            program.draw(p)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    fun release() {
        background.release()
        program.release()
    }

    /** Blurred video or picture over the background color, if any is ready. */
    private fun drawBackground(
        scene: CanvasScene,
        layers: List<LayerTexture>,
        cw: Float,
        ch: Float,
        aspect: Float,
        width: Int,
        height: Int,
    ) {
        val blur = scene.blur
        if (blur != null) {
            val key = blur.file.absolutePath
            var state: LayerTexture? = null
            for (i in layers.indices) if (layers[i].key == key) state = layers[i]
            if (state == null || !state.hasFrame) return
            val p = params
            p.crop[0] = blur.layer.crop.x
            p.crop[1] = blur.layer.crop.y
            p.crop[2] = blur.layer.crop.width
            p.crop[3] = blur.layer.crop.height
            p.quarterTurns = ((blur.rotationDegrees / 90) % 4 + 4) % 4
            p.hasTexture = true
            p.texture = state.texture
            System.arraycopy(state.texMatrix, 0, p.texMatrix, 0, 16)
            background.drawBlur(program, p, cw, ch, canvasFrame, width, height)
        } else if (scene.image != null) {
            background.drawImage(canvasFrame, aspect)
        }
    }

    companion object {
        /** Files to decode for [scene]: the drawn layers, then the blur source if it is not one of them. */
        fun wantedLayers(scene: CanvasScene): LinkedHashMap<String, SceneLayer> {
            val wanted = LinkedHashMap(scene.layers.associateBy { it.file.absolutePath })
            scene.blur?.let { wanted.putIfAbsent(it.file.absolutePath, it) }
            return wanted
        }
    }
}
