// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.video.CanvasRenderer
import io.github.higor0227.selfband.core.video.CanvasRendererFactory
import io.github.higor0227.selfband.core.video.CanvasScene
import io.github.higor0227.selfband.core.video.DecoderStartException
import io.github.higor0227.selfband.core.video.SceneLayer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal class GlCanvasRendererFactory @Inject constructor() : CanvasRendererFactory {
    override fun create(scene: CanvasScene, surface: Surface, width: Int, height: Int): CanvasRenderer =
        GlCanvasRenderer(scene, surface, width, height)
}

/**
 * [CanvasRenderer] on the caller's thread: its own EGL context drawing into the encoder
 * surface through [CanvasDrawer], one [FrameStepDecoder] per video. Frame-available callbacks
 * arrive on a small helper thread; the caller waits for each before latching.
 */
internal class GlCanvasRenderer(
    private val scene: CanvasScene,
    surface: Surface,
    private val width: Int,
    private val height: Int,
) : CanvasRenderer {
    private val callbacks = HandlerThread("SelfbandExportFrames").apply { start() }
    private val handler = Handler(callbacks.looper)
    private val egl = EglCore()
    private var window: EGLSurface? = null
    private var drawer: CanvasDrawer? = null
    private val layers = mutableListOf<Layer>()

    private class Layer(key: String, scene: SceneLayer, drawn: Boolean) : LayerTexture(key, scene, drawn) {
        val available = Semaphore(0)
        var decoder: FrameStepDecoder? = null

        override fun release() {
            decoder?.close()
            super.release()
        }
    }

    init {
        try {
            val target = checkNotNull(egl.createWindowSurface(surface)) { "cannot draw into the encoder surface" }
            window = target
            check(egl.makeCurrent(target)) { "eglMakeCurrent failed" }
            drawer = CanvasDrawer(handler).also { it.background.loadImageNow(scene.image) }
            val drawn = scene.layers.map { it.file.absolutePath }.toSet()
            for ((key, sceneLayer) in CanvasDrawer.wantedLayers(scene)) {
                val layer = Layer(key, sceneLayer, drawn = key in drawn)
                layers += layer
                layer.surfaceTexture.setOnFrameAvailableListener({ layer.available.release() }, handler)
                layer.decoder = if (sceneLayer.file.exists()) {
                    try {
                        FrameStepDecoder(sceneLayer.file, layer.surface, sceneLayer.frameRate)
                    } catch (e: Exception) {
                        throw DecoderStartException(layers.count { it.decoder != null }, e)
                    }
                } else {
                    Log.w(LOG_TAG, "export: missing video ${sceneLayer.file.name}")
                    null
                }
            }
        } catch (e: Exception) {
            releaseAll()
            throw e
        }
    }

    override fun render(position: Double, presentationNanos: Long) {
        for (i in layers.indices) {
            val layer = layers[i]
            val decoder = layer.decoder ?: continue
            val targetUs = ((position - layer.scene.startFrame) * 1_000_000.0 / scene.sampleRate).toLong()
            decoder.advanceTo(targetUs) {
                if (layer.available.tryAcquire(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    layer.latch()
                } else {
                    Log.w(LOG_TAG, "export: frame of ${layer.scene.file.name} did not arrive")
                }
            }
        }
        val target = checkNotNull(window)
        checkNotNull(drawer).draw(scene, layers, width, height)
        egl.setPresentationTime(target, presentationNanos)
        check(egl.swap(target)) { "eglSwapBuffers failed" }
    }

    override fun close() = releaseAll()

    private fun releaseAll() {
        layers.forEach { runCatching { it.release() } }
        layers.clear()
        drawer?.release()
        drawer = null
        window?.let {
            egl.makeNothingCurrent()
            egl.destroySurface(it)
        }
        window = null
        egl.release()
        callbacks.quitSafely()
    }

    private companion object {
        const val FRAME_TIMEOUT_MS = 2_000L
    }
}
