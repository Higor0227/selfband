// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.camera.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.github.higor0227.selfband.core.model.LOG_TAG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * H.264 encoder fed by a persistent input surface (the camera renders into it only while
 * recording) and written to MP4. Output buffers keep the camera's sensor timestamps, so the
 * first written sample tells exactly which frame the file starts with.
 *
 * One instance encodes one take: [prepare] → [start] → [finish]. All codec and muxer work
 * runs on the encoder thread.
 */
internal class VideoEncoder(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int,
) {
    data class Stats(val firstPtsUs: Long, val lastPtsUs: Long, val frames: Int)

    private val thread = HandlerThread("SelfbandEncoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null

    // Encoder-thread state.
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false
    private var outputFormat: MediaFormat? = null
    private var recording = false
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    @Volatile var frames = 0
        private set
    private var eos: CompletableDeferred<Unit>? = null

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size > 0 && recording && muxerStarted) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    muxer?.writeSampleData(track, buffer, info)
                    // Min/max rather than first/last: B-frames would reorder the output.
                    val pts = info.presentationTimeUs
                    if (firstPtsUs < 0 || pts < firstPtsUs) firstPtsUs = pts
                    if (pts > lastPtsUs) lastPtsUs = pts
                    frames++
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos?.complete(Unit)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputFormat = format
            startMuxerIfReady()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(LOG_TAG, "Video encoder error", e)
            eos?.complete(Unit)
        }
    }

    /** Configures and starts the codec, idle until frames arrive on the surface. */
    fun prepare(): Boolean = runCatching {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(callback, handler)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.setInputSurface(surface)
        c.start()
        codec = c
        true
    }.getOrElse {
        Log.e(LOG_TAG, "Video encoder prepare failed", it)
        false
    }

    /** Begins writing [file]. Call before the camera starts rendering into the surface. */
    suspend fun start(file: File, rotationDegrees: Int): Boolean {
        val done = CompletableDeferred<Boolean>()
        handler.post {
            done.complete(
                runCatching {
                    muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                        setOrientationHint(rotationDegrees)
                    }
                    track = -1
                    muxerStarted = false
                    firstPtsUs = -1
                    lastPtsUs = -1
                    frames = 0
                    recording = true
                    startMuxerIfReady()
                    true
                }.getOrElse {
                    Log.e(LOG_TAG, "Muxer start failed", it)
                    false
                },
            )
        }
        return done.await()
    }

    private fun startMuxerIfReady() {
        val m = muxer ?: return
        val f = outputFormat ?: return
        if (muxerStarted) return
        track = m.addTrack(f)
        m.start()
        muxerStarted = true
    }

    /** Drains the encoder, finalizes the MP4 and releases the codec. */
    suspend fun finish(): Stats? {
        val c = codec ?: return null
        val done = CompletableDeferred<Unit>()
        eos = done
        runCatching { c.signalEndOfInputStream() }
        if (withTimeoutOrNull(EOS_TIMEOUT_MS) { done.await() } == null) {
            Log.w(LOG_TAG, "Video encoder: end of stream not seen, finalizing anyway")
        }
        val result = CompletableDeferred<Stats?>()
        handler.post {
            recording = false
            val stats = if (frames > 0) Stats(firstPtsUs, lastPtsUs, frames) else null
            runCatching { if (muxerStarted) muxer?.stop() }.onFailure { Log.e(LOG_TAG, "Muxer stop failed", it) }
            runCatching { muxer?.release() }
            muxer = null
            muxerStarted = false
            runCatching { c.stop() }
            runCatching { c.release() }
            codec = null
            outputFormat = null
            result.complete(stats)
        }
        return result.await()
    }

    fun release() {
        handler.post {
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            muxer = null
            codec = null
        }
        thread.quitSafely()
    }

    private companion object {
        const val EOS_TIMEOUT_MS = 3_000L
    }
}
