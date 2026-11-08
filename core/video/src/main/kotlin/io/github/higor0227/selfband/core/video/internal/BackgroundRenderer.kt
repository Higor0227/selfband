// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.util.Log
import io.github.higor0227.selfband.core.model.CanvasLayout
import io.github.higor0227.selfband.core.model.LOG_TAG
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Canvas background beyond the solid color (SPEC 2.6): a picture covering the canvas, or a
 * video drawn small into a framebuffer, blurred there with separable Gaussian passes and
 * scaled up over the canvas (the downscale does most of the blurring cheaply). GL thread only;
 * [handler] is the GL thread's.
 */
internal class BackgroundRenderer(private val handler: Handler) {
    private val program = TextureProgram()
    private val full = floatArrayOf(0f, 0f, 1f, 1f)
    private val crop = FloatArray(4)

    // Blur targets: two framebuffers ping-ponged.
    private val fbo = IntArray(2)
    private val fboTex = IntArray(2)
    private var fboW = 0
    private var fboH = 0

    // Picture.
    private var wantedImage: String? = null
    private var imageTexture = 0
    private var imageAspect = 1f
    private var pending: Bitmap? = null

    /** Picture to show from now on (null = none). Decoded off the GL thread; [onLoaded] redraws. */
    fun setImage(file: File?, onLoaded: () -> Unit) {
        val path = file?.absolutePath
        if (path == wantedImage) return
        wantedImage = path
        pending?.recycle()
        pending = null
        deleteImageTexture()
        if (file == null) return
        Thread({
            val bitmap = decode(file)
            handler.post {
                if (wantedImage == path && bitmap != null) {
                    pending = bitmap
                    onLoaded()
                } else {
                    bitmap?.recycle()
                }
            }
        }, "SelfbandBgImage").start()
    }

    /** Loads the picture right away on the GL thread (export: the first frame must have it). */
    fun loadImageNow(file: File?) {
        wantedImage = file?.absolutePath
        pending?.recycle()
        pending = null
        deleteImageTexture()
        if (file != null) decode(file)?.let { upload(it) }
    }

    /** Draws the picture over the canvas ([canvasFrame] normalized to the viewport). */
    fun drawImage(canvasFrame: FloatArray, canvasAspect: Float): Boolean {
        pending?.let { upload(it) }
        if (imageTexture == 0) return false
        val c = CanvasLayout.coverCrop(imageAspect, canvasAspect)
        crop[0] = c.x; crop[1] = c.y; crop[2] = c.width; crop[3] = c.height
        program.draw(imageTexture, canvasFrame, crop, flipY = false)
        return true
    }

    /**
     * Draws [p]'s video (texture, matrix, rotation and crop already set) blurred over the
     * canvas: [canvasW] × [canvasH] pixels at [canvasFrame] (normalized to the surface of
     * [surfaceW] × [surfaceH]). Leaves blending off and the scissor test on (box unchanged).
     */
    fun drawBlur(
        layers: LayerProgram,
        p: LayerProgram.Params,
        canvasW: Float,
        canvasH: Float,
        canvasFrame: FloatArray,
        surfaceW: Int,
        surfaceH: Int,
    ) {
        val w = max(1, (canvasW / DOWNSCALE).roundToInt())
        val h = max(1, (canvasH / DOWNSCALE).roundToInt())
        ensureTargets(w, h)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glViewport(0, 0, w, h)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        System.arraycopy(full, 0, p.frame, 0, 4)
        p.widthPx = w.toFloat()
        p.heightPx = h.toFloat()
        p.radiusPx = 0f
        p.borderPx = 0f
        p.opacity = 1f
        layers.draw(p)
        repeat(PASSES) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[1])
            program.draw(fboTex[0], full, full, flipY = true, stepX = 1f / w)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            program.draw(fboTex[1], full, full, flipY = true, stepY = 1f / h)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        program.draw(fboTex[0], canvasFrame, full, flipY = true, dim = DIM)
    }

    fun release() {
        pending?.recycle()
        pending = null
        wantedImage = null
        deleteImageTexture()
        deleteTargets()
        program.release()
    }

    private fun ensureTargets(w: Int, h: Int) {
        if (w == fboW && h == fboH && fbo[0] != 0) return
        deleteTargets()
        GLES30.glGenTextures(2, fboTex, 0)
        GLES30.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex[i])
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            setFilters(GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex[i], 0,
            )
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        fboW = w
        fboH = h
    }

    private fun deleteTargets() {
        if (fbo[0] == 0) return
        GLES30.glDeleteFramebuffers(2, fbo, 0)
        GLES30.glDeleteTextures(2, fboTex, 0)
        fbo.fill(0)
        fboTex.fill(0)
        fboW = 0
        fboH = 0
    }

    private fun upload(bitmap: Bitmap) {
        pending = null
        deleteImageTexture()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        imageTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imageTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        setFilters(GLES30.GL_LINEAR_MIPMAP_LINEAR)
        imageAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        bitmap.recycle()
    }

    private fun deleteImageTexture() {
        if (imageTexture == 0) return
        GLES30.glDeleteTextures(1, intArrayOf(imageTexture), 0)
        imageTexture = 0
    }

    private fun setFilters(minFilter: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, minFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun decode(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            .also { if (it == null) Log.w(LOG_TAG, "canvas: could not decode ${file.name}") }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "canvas: could not decode ${file.name}", e)
        null
    }

    private companion object {
        /** Blur framebuffer size relative to the canvas. */
        const val DOWNSCALE = 8f
        /** Horizontal + vertical pass pairs. */
        const val PASSES = 2
        /** The blurred video is darkened a little so the layers stand out. */
        const val DIM = 0.75f
        const val MAX_IMAGE_SIDE = 2048
    }
}
