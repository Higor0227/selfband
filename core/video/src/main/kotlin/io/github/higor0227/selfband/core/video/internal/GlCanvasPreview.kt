// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.value
import io.github.higor0227.selfband.core.video.CanvasPreview
import io.github.higor0227.selfband.core.video.CanvasPreviewFactory
import io.github.higor0227.selfband.core.video.CanvasScene
import io.github.higor0227.selfband.core.video.PositionSource
import io.github.higor0227.selfband.core.video.SceneLayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.min

internal class GlCanvasPreviewFactory @Inject constructor() : CanvasPreviewFactory {
    override fun create(): CanvasPreview = GlCanvasPreview()
}

/**
 * OpenGL ES 3 compositor + per-layer decoders (SPEC 3.5). Everything GL runs on one render
 * thread with a Looper; Choreographer drives it at the display rate. Each vsync it publishes
 * the target media time of every layer (from the audio clock, ahead by the display latency),
 * latches the frames the decoders delivered and redraws when something changed.
 */
internal class GlCanvasPreview : CanvasPreview {
    private val thread = HandlerThread("SelfbandCanvas").apply { start() }
    private val handler = Handler(thread.looper)

    // Render thread only from here on.
    private lateinit var egl: EglCore
    private lateinit var program: LayerProgram
    private lateinit var background: BackgroundRenderer
    private var ready = false
    private var window: EGLSurface? = null
    private var width = 0
    private var height = 0
    private var scene: CanvasScene? = null
    private var position = PositionSource { 0.0 }
    private val layers = mutableListOf<LayerState>()
    private var dirty = true
    private var callbackPosted = false
    private var paused = false
    private var lastFrameNanos = 0L
    private var vsyncNanos = DEFAULT_VSYNC_NANOS
    private val params = LayerProgram.Params()
    private val canvasFrame = FloatArray(4)
    private val frameCallback = Choreographer.FrameCallback { onVsync(it) }

    private class LayerState(
        val key: String,
        var scene: SceneLayer,
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        val decoder: LayerDecoder?,
        /** False for the video that only feeds the blurred background. */
        var drawn: Boolean,
    ) {
        val frameAvailable = AtomicBoolean(false)
        var hasFrame = false
        val texMatrix = FloatArray(16)
        /** Frames latched since the last diagnostics line. */
        var latched = 0
    }

    init {
        handler.post {
            try {
                egl = EglCore()
                program = LayerProgram()
                background = BackgroundRenderer(handler)
                ready = true
            } catch (e: Exception) {
                Log.e(LOG_TAG, "canvas: GL init failed", e)
            }
        }
    }

    override fun attach(surface: Surface, width: Int, height: Int) {
        handler.post {
            if (!ready) return@post
            window?.let { egl.destroySurface(it) }
            window = egl.createWindowSurface(surface)
            if (window == null) Log.w(LOG_TAG, "canvas: could not create window surface")
            this.width = width
            this.height = height
            dirty = true
            postCallback()
        }
    }

    override fun resize(width: Int, height: Int) {
        handler.post {
            this.width = width
            this.height = height
            dirty = true
        }
    }

    override fun detach() {
        val done = CountDownLatch(1)
        handler.post {
            window?.let {
                egl.makeNothingCurrent()
                egl.destroySurface(it)
            }
            window = null
            done.countDown()
        }
        done.await(DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    override fun setScene(scene: CanvasScene) {
        handler.post {
            this.scene = scene
            if (ready) {
                if (!paused) syncLayers(scene)
                background.setImage(scene.image) { dirty = true }
            }
            dirty = true
        }
    }

    override fun setPosition(source: PositionSource) {
        handler.post {
            position = source
            dirty = true
        }
    }

    /** Pausing frees the decoders (another screen may need them); resuming recreates them. */
    override fun setPaused(paused: Boolean) {
        handler.post {
            if (this.paused == paused) return@post
            this.paused = paused
            if (paused) {
                layers.forEach { it.release() }
                layers.clear()
            } else {
                scene?.let { if (ready) syncLayers(it) }
                dirty = true
                postCallback()
            }
        }
    }

    override fun release() {
        handler.post {
            window?.let { egl.destroySurface(it) }
            window = null
            layers.forEach { it.release() }
            layers.clear()
            if (ready) {
                background.release()
                program.release()
                egl.release()
                ready = false
            }
            thread.quitSafely()
        }
    }

    /**
     * Keeps decoders of files still in the scene (layers and blurred background); starts new
     * ones; frees the rest. A video both drawn and blurred has one decoder.
     */
    private fun syncLayers(scene: CanvasScene) {
        val drawn = scene.layers.associateBy { it.file.absolutePath }
        val wanted = LinkedHashMap(drawn)
        scene.blur?.let { wanted.putIfAbsent(it.file.absolutePath, it) }
        val iterator = layers.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            val next = wanted[state.key]
            if (next == null) {
                state.release()
                iterator.remove()
            } else {
                state.scene = next
                state.drawn = state.key in drawn
            }
        }
        val existing = layers.map { it.key }.toSet()
        for ((key, layer) in wanted) {
            if (key in existing) continue
            val texture = LayerProgram.createExternalTexture()
            val surfaceTexture = SurfaceTexture(texture)
            val surface = Surface(surfaceTexture)
            val decoder = if (layer.file.exists()) {
                LayerDecoder(layer.file, surface, layer.frameRate).also { it.start() }
            } else {
                Log.w(LOG_TAG, "canvas: missing video ${layer.file.name}")
                null
            }
            val state = LayerState(key, layer, texture, surfaceTexture, surface, decoder, drawn = key in drawn)
            surfaceTexture.setOnFrameAvailableListener({ state.frameAvailable.set(true) }, handler)
            layers += state
        }
        // Draw order follows the scene.
        val order = scene.layers.withIndex().associate { it.value.file.absolutePath to it.index }
        layers.sortBy { order[it.key] ?: Int.MAX_VALUE }
    }

    private fun LayerState.release() {
        decoder?.release()
        surface.release()
        surfaceTexture.release()
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    private fun postCallback() {
        if (callbackPosted || window == null || paused) return
        callbackPosted = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onVsync(frameTimeNanos: Long) {
        callbackPosted = false
        if (paused) return
        val surface = window ?: return
        val scene = scene
        if (lastFrameNanos != 0L) {
            val delta = frameTimeNanos - lastFrameNanos
            if (delta in MIN_VSYNC_NANOS..MAX_VSYNC_NANOS) vsyncNanos = (vsyncNanos * 7 + delta) / 8
        }
        lastFrameNanos = frameTimeNanos

        // What will be on screen when the next draw is displayed.
        val presentNanos = frameTimeNanos + vsyncNanos * (1 + DISPLAY_LATENCY_VSYNCS)
        if (scene != null) {
            val pos = position.positionAt(presentNanos)
            lastPosition = pos
            for (i in layers.indices) {
                val state = layers[i]
                val decoder = state.decoder ?: continue
                decoder.targetUs = ((pos - state.scene.startFrame) * 1_000_000.0 / scene.sampleRate).toLong()
            }
        }

        // Latch delivered frames.
        if (egl.makeCurrent(surface)) {
            for (i in layers.indices) {
                val state = layers[i]
                if (state.frameAvailable.getAndSet(false)) {
                    try {
                        state.surfaceTexture.updateTexImage()
                        state.surfaceTexture.getTransformMatrix(state.texMatrix)
                        state.hasFrame = true
                        state.latched++
                        dirty = true
                    } catch (e: RuntimeException) {
                        Log.w(LOG_TAG, "canvas: updateTexImage failed", e)
                    }
                    state.decoder?.markConsumed()
                }
            }
            if (dirty && scene != null) {
                draw(scene)
                if (egl.swap(surface)) dirty = false
            }
        }
        logDiagnostics(frameTimeNanos)
        postCallback()
    }

    private var lastLogNanos = 0L
    private var lastPosition = 0.0
    private var lastDropped = 0L

    /** Every few seconds while frames flow: frames shown and dropped per layer. */
    private fun logDiagnostics(nowNanos: Long) {
        if (nowNanos - lastLogNanos < LOG_INTERVAL_NANOS) return
        lastLogNanos = nowNanos
        val dropped = layers.sumOf { it.decoder?.droppedFrames ?: 0L }
        if (layers.none { it.latched > 0 } && dropped == lastDropped) return
        lastDropped = dropped
        val line = layers.joinToString(" | ") {
            "${it.key.substringAfterLast('/').take(8)} shown=${it.latched} dropped=${it.decoder?.droppedFrames ?: -1}"
        }
        layers.forEach { it.latched = 0 }
        Log.d(LOG_TAG, "canvas: pos=${lastPosition.toLong()} vsync=${vsyncNanos / 1000}us $line")
    }

    private fun draw(scene: CanvasScene) {
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
        drawBackground(scene, cw, ch, aspect)

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

    /** Blurred video or picture over the background color, if any is ready. */
    private fun drawBackground(scene: CanvasScene, cw: Float, ch: Float, aspect: Float) {
        val blur = scene.blur
        if (blur != null) {
            val key = blur.file.absolutePath
            var state: LayerState? = null
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

    private companion object {
        const val DEFAULT_VSYNC_NANOS = 16_666_667L
        const val MIN_VSYNC_NANOS = 4_000_000L
        const val MAX_VSYNC_NANOS = 50_000_000L
        /**
         * Frames between a draw and its appearance on screen (TextureView composition +
         * display). Estimate; the per-take manual offset absorbs what is left.
         */
        const val DISPLAY_LATENCY_VSYNCS = 2
        const val DETACH_TIMEOUT_MS = 1_000L
        const val LOG_INTERVAL_NANOS = 3_000_000_000L
    }
}
