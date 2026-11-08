// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import io.github.higor0227.selfband.core.model.LOG_TAG
import java.io.File

/**
 * Export twin of [LayerDecoder]: decodes one video into [surface] on the caller's thread,
 * stepping forward to each requested media time. It picks frames with the same rule as the
 * preview (the frame nearest the target; frames whose successor is also due are dropped;
 * before the first frame the first one, past the end the last one), but never drops a frame
 * for being slow: export time is not real time.
 */
internal class FrameStepDecoder(file: File, private val surface: Surface, frameRate: Int) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val frameUs = 1_000_000L / frameRate.coerceIn(1, 240)
    private val firstUs: Long
    private val lastUs: Long
    private val info = MediaCodec.BufferInfo()
    private var started = false
    private var inputDone = false
    private var outputDone = false
    private var pending = -1
    private var pendingUs = 0L

    init {
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("no video track in ${file.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            // Rotation is applied by the compositor's shader; the codec must not rotate too.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, surface, null, 0)
            codec.start()
            firstUs = extractor.sampleTime.coerceAtLeast(0)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                Long.MAX_VALUE / 2
            }
            lastUs = firstUs + (durationUs - frameUs).coerceAtLeast(0)
        } catch (e: Exception) {
            extractor.release()
            throw e
        }
    }

    /**
     * Renders every frame up to the one shown at media time [targetUs] (relative to the first
     * frame), calling [onRendered] after each so the caller can latch it before the next.
     * Targets must not go backwards.
     */
    fun advanceTo(targetUs: Long, onRendered: () -> Unit) {
        val target = (firstUs + targetUs.coerceAtLeast(0)).coerceAtMost(lastUs)
        if (!started) {
            started = true
            extractor.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }
        val half = frameUs / 2
        var idle = 0
        while (true) {
            if (pending < 0) {
                if (outputDone) return
                feed()
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index < 0) {
                    // A decoder that stops producing must not hang the export.
                    if (++idle > MAX_IDLE_POLLS) {
                        Log.w(LOG_TAG, "export: decoder stalled at ${target}us")
                        outputDone = true
                        return
                    }
                    continue
                }
                idle = 0
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (eos) outputDone = true
                if (info.size > 0 || !eos) {
                    pending = index
                    pendingUs = info.presentationTimeUs
                } else {
                    codec.releaseOutputBuffer(index, false)
                    continue
                }
            }
            when {
                // The next frame is due too: skip this one.
                pendingUs + frameUs <= target - half -> codec.releaseOutputBuffer(pending, false)
                // The one to show (or an earlier one still to pass through on the way).
                pendingUs <= target + half -> {
                    codec.releaseOutputBuffer(pending, true)
                    onRendered()
                }
                // In the future: keep it for a later target.
                else -> return
            }
            pending = -1
        }
    }

    private fun feed() {
        while (!inputDone) {
            val index = codec.dequeueInputBuffer(0)
            if (index < 0) return
            val buffer = codec.getInputBuffer(index) ?: return
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
            } else {
                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        /** 2 s without any decoded frame. */
        const val MAX_IDLE_POLLS = 200
    }
}
