// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import io.github.higor0227.selfband.core.model.LOG_TAG
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes one video file into [surface] (a SurfaceTexture's) on its own thread, following a
 * target media time set by the render thread (SPEC 3.5):
 * - a frame is rendered when it is the one nearest the target;
 * - frames that are already late are released without rendering (dropped), never waited for;
 * - a jump backwards, or far forwards, seeks to the previous sync frame and decodes up to it.
 * Only one rendered frame is in flight: the next one waits until the render thread latched
 * the previous one ([markConsumed]), so the SurfaceTexture never queues stale frames.
 */
internal class LayerDecoder(
    private val file: File,
    private val surface: Surface,
    frameRate: Int,
) {
    /** Media time (µs) the render thread wants on screen. */
    @Volatile var targetUs: Long = 0

    private val frameUs = 1_000_000L / frameRate.coerceIn(1, 240)
    private val consumed = AtomicBoolean(true)
    @Volatile private var running = true
    private val thread = Thread(::run, "SelfbandDecoder").apply { isDaemon = true }

    /** Frames released without rendering (diagnostics). */
    @Volatile var droppedFrames = 0L
        private set

    fun start() = thread.start()

    /** Called by the render thread after updateTexImage(). */
    fun markConsumed() = consumed.set(true)

    /** Stops the thread and frees the codec. Blocks until done. */
    fun release() {
        running = false
        thread.join(JOIN_TIMEOUT_MS)
    }

    private fun run() {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            // Rotation is applied by the compositor's shader; the codec must not rotate too.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, surface, null, 0)
            codec.start()
            // Target times are relative to the first frame; the file's may not start at 0.
            val firstUs = extractor.sampleTime.coerceAtLeast(0)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                Long.MAX_VALUE / 2
            }
            loop(extractor, codec, firstUs, firstUs + (durationUs - frameUs).coerceAtLeast(0))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "decoder failed for ${file.name}", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Before the first frame shows the first frame; past the end holds [lastUs]. */
    private fun loop(extractor: MediaExtractor, codec: MediaCodec, firstUs: Long, lastUs: Long) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var pending = -1
        var pendingUs = 0L
        /** Timestamp of the newest decoded frame (pending, rendered or dropped). */
        var headUs = Long.MIN_VALUE
        /** Timestamp of the last frame rendered since the last seek. */
        var shownUs = Long.MIN_VALUE
        var needsSeek = true
        val half = frameUs / 2

        while (running) {
            val target = (firstUs + targetUs.coerceAtLeast(0)).coerceAtMost(lastUs)
            if (!needsSeek && headUs != Long.MIN_VALUE) {
                // An earlier frame than the one on screen is nearer: go back. (The frame held
                // for later is ahead by design and must not trigger this.)
                val behind = shownUs != Long.MIN_VALUE && target < shownUs - half
                val farAhead = !outputDone && target > headUs + SEEK_AHEAD_US
                needsSeek = behind || farAhead
            }
            if (needsSeek) {
                needsSeek = false
                extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                codec.flush() // invalidates the pending output buffer
                pending = -1
                inputDone = false
                outputDone = false
                headUs = Long.MIN_VALUE
                shownUs = Long.MIN_VALUE
            }
            var progressed = false

            // Feed compressed samples.
            while (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) break
                val buffer = codec.getInputBuffer(index) ?: break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
                progressed = true
            }

            // Take the next decoded frame.
            if (pending < 0 && !outputDone) {
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    progressed = true
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (eos) outputDone = true
                    if (info.size > 0 || !eos) {
                        pending = index
                        pendingUs = info.presentationTimeUs
                        headUs = pendingUs
                    } else {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }

            // Render, drop or keep holding it.
            if (pending >= 0) {
                when {
                    // The next frame is due too: this one is late.
                    pendingUs + frameUs <= target - half -> {
                        codec.releaseOutputBuffer(pending, false)
                        droppedFrames++
                        pending = -1
                        progressed = true
                    }
                    // Nearest to the target: show it once the previous one was latched.
                    pendingUs <= target + half -> if (consumed.compareAndSet(true, false)) {
                        codec.releaseOutputBuffer(pending, true)
                        shownUs = pendingUs
                        pending = -1
                        progressed = true
                    }
                    // Still in the future: hold it.
                    else -> Unit
                }
            }

            // Holding a frame for later or at the end: nothing to do until the target moves.
            if (!progressed) Thread.sleep(if (pending >= 0 || outputDone) HOLD_SLEEP_MS else IDLE_SLEEP_MS)
        }
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 2_000L
        const val IDLE_SLEEP_MS = 2L
        const val HOLD_SLEEP_MS = 4L
        /** Decoding forward is cheaper than seeking for jumps up to this much. */
        const val SEEK_AHEAD_US = 1_500_000L
        const val JOIN_TIMEOUT_MS = 2_000L
    }
}
