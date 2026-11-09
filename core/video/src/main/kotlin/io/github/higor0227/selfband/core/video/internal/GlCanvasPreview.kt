// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.ProxyMode
import io.github.higor0227.selfband.core.model.Proxies
import io.github.higor0227.selfband.core.model.SelfbandLog
import io.github.higor0227.selfband.core.video.CanvasPreview
import io.github.higor0227.selfband.core.video.CanvasPreviewFactory
import io.github.higor0227.selfband.core.video.CanvasScene
import io.github.higor0227.selfband.core.video.DecoderInstances
import io.github.higor0227.selfband.core.video.PositionSource
import io.github.higor0227.selfband.core.video.SceneLayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

internal class GlCanvasPreviewFactory @Inject constructor() : CanvasPreviewFactory {
    override fun create(): CanvasPreview = GlCanvasPreview()
}

/**
 * OpenGL ES 3 compositor + per-layer decoders (SPEC 3.5). Everything GL runs on one render
 * thread with a Looper; Choreographer drives it at the display rate. Each vsync it publishes
 * the target media time of every layer (from the audio clock, ahead by the display latency),
 * latches the frames the decoders delivered and redraws when something changed.
 *
 * Proxies (SPEC 3.5): the scene drawn is the one given, or the same scene on the layers'
 * proxies when [Proxies.use] says so for the current mode, decoder capacity and measured
 * speed.
 */
internal class GlCanvasPreview : CanvasPreview {
    private val thread = HandlerThread("SelfbandCanvas").apply { start() }
    private val handler = Handler(thread.looper)

    // Render thread only from here on.
    private lateinit var egl: EglCore
    private lateinit var drawer: CanvasDrawer
    private var ready = false
    private var window: EGLSurface? = null
    private var width = 0
    private var height = 0
    /** The scene as given; [scene] is what is drawn (possibly on proxies). */
    private var source: CanvasScene? = null
    private var scene: CanvasScene? = null
    private var proxyMode = ProxyMode.AUTO
    /** The originals need more decoders than the device has. */
    private var overCapacity = false
    /** Measured: the originals are not decoded in time (sticky for this preview). */
    private var slow = false
    private var slowIntervals = 0
    private var usingProxies = false
    /** Reads video formats for the capacity check, away from the render thread. */
    private val capacityExecutor = Executors.newSingleThreadExecutor { Thread(it, "SelfbandCanvasCaps").apply { isDaemon = true } }
    private var position = PositionSource { 0.0 }
    private val layers = mutableListOf<LayerState>()
    private var dirty = true
    private var callbackPosted = false
    private var paused = false
    private var lastFrameNanos = 0L
    private var vsyncNanos = DEFAULT_VSYNC_NANOS
    private val frameCallback = Choreographer.FrameCallback { onVsync(it) }

    private class LayerState(key: String, scene: SceneLayer, drawn: Boolean) : LayerTexture(key, scene, drawn) {
        var decoder: LayerDecoder? = null
        val frameAvailable = AtomicBoolean(false)
        /** Frames latched since the last diagnostics line. */
        var latched = 0

        override fun release() {
            decoder?.release()
            super.release()
        }
    }

    init {
        handler.post {
            try {
                egl = EglCore()
                drawer = CanvasDrawer(handler)
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
            source = scene
            applyScene()
        }
        val originals = scene.decodedLayers.map { it.file }
        capacityExecutor.execute {
            val over = DecoderInstances.shortfall(originals) != null
            handler.post {
                if (source === scene && over != overCapacity) {
                    overCapacity = over
                    applyScene()
                }
            }
        }
    }

    override fun setProxyMode(mode: ProxyMode) {
        handler.post {
            if (proxyMode == mode) return@post
            proxyMode = mode
            applyScene()
        }
    }

    /** Render thread: draws [source] or its proxy version, re-syncing the decoders on a change. */
    private fun applyScene() {
        val src = source ?: return
        val videos = src.decodedLayers.size
        val proxies = Proxies.use(proxyMode, videos, overCapacity, slow)
        if (proxies != usingProxies) {
            usingProxies = proxies
            Log.i(LOG_TAG, "canvas: proxies ${if (proxies) "on" else "off"} (mode=$proxyMode videos=$videos overCapacity=$overCapacity slow=$slow)")
        }
        val next = if (proxies) src.withProxies() else src
        if (next == scene) return
        scene = next
        if (ready) {
            if (!paused) syncLayers(next)
            drawer.background.setImage(next.image) { dirty = true }
        }
        dirty = true
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
                drawer.release()
                egl.release()
                ready = false
            }
            thread.quitSafely()
        }
        capacityExecutor.shutdown()
    }

    /**
     * Keeps decoders of files still in the scene (layers and blurred background); starts new
     * ones; frees the rest. A video both drawn and blurred has one decoder.
     */
    private fun syncLayers(scene: CanvasScene) {
        val drawn = scene.layers.associateBy { it.file.absolutePath }
        val wanted = CanvasDrawer.wantedLayers(scene)
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
            val state = LayerState(key, layer, drawn = key in drawn)
            state.surfaceTexture.setOnFrameAvailableListener({ state.frameAvailable.set(true) }, handler)
            state.decoder = if (layer.file.exists()) {
                LayerDecoder(layer.file, state.surface, layer.frameRate).also { it.start() }
            } else {
                Log.w(LOG_TAG, "canvas: missing video ${layer.file.name}")
                null
            }
            layers += state
        }
        // Draw order follows the scene.
        val order = scene.layers.withIndex().associate { it.value.file.absolutePath to it.index }
        layers.sortBy { order[it.key] ?: Int.MAX_VALUE }
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
                    if (state.latch()) {
                        state.latched++
                        dirty = true
                    }
                    state.decoder?.markConsumed()
                }
            }
            if (dirty && scene != null) {
                drawer.draw(scene, layers, width, height)
                if (egl.swap(surface)) dirty = false
            }
        }
        logDiagnostics(frameTimeNanos)
        postCallback()
    }

    private var lastLogNanos = 0L
    private var lastPosition = 0.0
    private var lastDropped = 0L

    /**
     * Every few seconds while frames flow, with verbose logging on (SPEC 3.8): frames shown and
     * dropped per layer. Always: the speed
     * measurement: originals dropping too many frames for [SLOW_INTERVALS] intervals in a row
     * mark the preview slow (proxies from then on, in automatic mode).
     */
    private fun logDiagnostics(nowNanos: Long) {
        if (nowNanos - lastLogNanos < LOG_INTERVAL_NANOS) return
        lastLogNanos = nowNanos
        val dropped = layers.sumOf { it.decoder?.droppedFrames ?: 0L }
        val droppedNow = (dropped - lastDropped).coerceAtLeast(0)
        val shownNow = layers.sumOf { it.latched.toLong() }
        if (!usingProxies && !slow) {
            val frames = droppedNow + shownNow
            slowIntervals = if (frames >= SLOW_MIN_FRAMES && droppedNow > frames * SLOW_DROP_RATIO) slowIntervals + 1 else 0
            if (slowIntervals >= SLOW_INTERVALS) {
                slow = true
                Log.i(LOG_TAG, "canvas: originals too slow (dropped $droppedNow of $frames frames)")
                applyScene()
            }
        }
        if (layers.none { it.latched > 0 } && dropped == lastDropped) return
        lastDropped = dropped
        if (!SelfbandLog.verbose) {
            layers.forEach { it.latched = 0 }
            return
        }
        val line = layers.joinToString(" | ") {
            "${it.key.substringAfterLast('/').take(8)} shown=${it.latched} dropped=${it.decoder?.droppedFrames ?: -1}"
        }
        layers.forEach { it.latched = 0 }
        Log.d(LOG_TAG, "canvas: pos=${lastPosition.toLong()} vsync=${vsyncNanos / 1000}us $line")
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
        /** Speed measurement: share of dropped frames, over intervals with at least this many frames. */
        const val SLOW_DROP_RATIO = 0.25
        const val SLOW_MIN_FRAMES = 30L
        const val SLOW_INTERVALS = 2
    }
}
