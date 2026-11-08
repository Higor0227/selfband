// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.higor0227.selfband.core.model.Proxies
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Re-encodes one take video as its proxy (SPEC 3.5) on the caller's thread: decode into a
 * SurfaceTexture, draw it scaled into the encoder's input surface, mux. Every frame keeps its
 * presentation time and the rotation stays in the file's metadata, so the proxy shows exactly
 * the same frames at the same moments as the original, only smaller.
 */
internal class ProxyTranscoder(
    private val source: File,
    private val target: File,
    /** Checked between frames; true abandons the work. */
    private val shouldStop: () -> Boolean,
) {
    /** Encoded size of the last proxy made. */
    var size: Pair<Int, Int> = 0 to 0
        private set

    /** Returns false if abandoned through [shouldStop] (the target is then incomplete). */
    fun run(): Boolean {
        val extractor = MediaExtractor()
        val callbacks = HandlerThread("SelfbandProxyFrames").apply { start() }
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var egl: EglCore? = null
        var window: EGLSurface? = null
        var inputSurface: Surface? = null
        var program: LayerProgram? = null
        var texture = 0
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("no video track in ${source.name}")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val width = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = inFormat.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
            val frameRate = inFormat.intOrNull(MediaFormat.KEY_FRAME_RATE) ?: DEFAULT_FRAME_RATE
            val (w, h) = Proxies.size(width, height)
            size = w to h

            val outFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, Proxies.bitRate(w, h, frameRate))
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, Proxies.KEY_FRAME_INTERVAL_S)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = encoder.createInputSurface()
            encoder.start()

            egl = EglCore()
            window = checkNotNull(egl.createWindowSurface(inputSurface)) { "cannot draw into the encoder surface" }
            check(egl.makeCurrent(window)) { "eglMakeCurrent failed" }
            program = LayerProgram()
            texture = LayerProgram.createExternalTexture()
            val available = Semaphore(0)
            surfaceTexture = SurfaceTexture(texture).apply {
                setOnFrameAvailableListener({ available.release() }, Handler(callbacks.looper))
            }
            decoderSurface = Surface(surfaceTexture)

            // Rotation stays metadata (as in the original); the codec must not apply it.
            inFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
            decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(inFormat, decoderSurface, null, 0)
                start()
            }
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                if (rotation in setOf(90, 180, 270)) setOrientationHint(rotation)
            }

            val params = LayerProgram.Params().apply {
                frame[2] = 1f; frame[3] = 1f
                crop[2] = 1f; crop[3] = 1f
                widthPx = w.toFloat()
                heightPx = h.toFloat()
                this.texture = texture
                hasTexture = true
            }
            GLES30.glViewport(0, 0, w, h)
            GLES30.glDisable(GLES30.GL_BLEND)

            val info = MediaCodec.BufferInfo()
            val outInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decodeDone = false
            var encodeDone = false
            var muxTrack = -1
            while (!encodeDone) {
                if (shouldStop()) return false
                while (!inputDone) {
                    val index = decoder.dequeueInputBuffer(0)
                    if (index < 0) break
                    val buffer = decoder.getInputBuffer(index) ?: break
                    val n = extractor.readSampleData(buffer, 0)
                    if (n < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                if (!decodeDone) {
                    val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val render = info.size > 0 || !eos
                        decoder.releaseOutputBuffer(index, render)
                        if (render) {
                            check(available.tryAcquire(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "decoded frame did not arrive" }
                            surfaceTexture.updateTexImage()
                            surfaceTexture.getTransformMatrix(params.texMatrix)
                            program.draw(params)
                            egl.setPresentationTime(window, info.presentationTimeUs * 1000)
                            check(egl.swap(window)) { "eglSwapBuffers failed" }
                        }
                        if (eos) {
                            encoder.signalEndOfInputStream()
                            decodeDone = true
                        }
                    }
                }
                // Drain the encoder; once all input is in, wait for its end of stream.
                while (true) {
                    val index = encoder.dequeueOutputBuffer(outInfo, if (decodeDone) TIMEOUT_US else 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        continue
                    }
                    if (index < 0) continue
                    val config = outInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && outInfo.size > 0 && muxerStarted) {
                        val data = checkNotNull(encoder.getOutputBuffer(index))
                        data.position(outInfo.offset)
                        data.limit(outInfo.offset + outInfo.size)
                        muxer.writeSampleData(muxTrack, data, outInfo)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (outInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encodeDone = true
                        break
                    }
                }
            }
            muxer.stop()
            muxerStarted = false
            return true
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            decoderSurface?.release()
            surfaceTexture?.release()
            program?.release()
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            egl?.let { core ->
                window?.let {
                    core.makeNothingCurrent()
                    core.destroySurface(it)
                }
                core.release()
            }
            inputSurface?.release()
            callbacks.quitSafely()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? {
        if (!containsKey(key)) return null
        return runCatching { getInteger(key) }.recoverCatching { getFloat(key).toInt() }.getOrNull()
    }

    private companion object {
        const val DEFAULT_FRAME_RATE = 30
        const val TIMEOUT_US = 10_000L
        const val FRAME_TIMEOUT_MS = 2_000L
    }
}
