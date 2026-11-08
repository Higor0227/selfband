// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import io.github.higor0227.selfband.core.audio.OfflineMix
import io.github.higor0227.selfband.core.export.VideoSpec
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.video.CanvasRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** The video could not be encoded on this device. */
internal class VideoExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Encodes the canvas and the mix into an MP4 (SPEC 2.7): the [CanvasRenderer] draws each
 * video frame into an H.264 encoder's input surface; the offline mix feeds an AAC encoder;
 * MediaMuxer interleaves both. The audio frame position is the clock: video frame k shows
 * project position `start + k · sampleRate / frameRate` and is stamped k / frameRate, while
 * audio sample n is stamped n / sampleRate, both from 0. The video is then delayed by the
 * measured AAC encoder delay ([AacDelay]), the silence the encoder puts before the sound.
 *
 * Runs entirely on the calling thread (the renderer's EGL context lives there).
 */
internal class Mp4Export(
    private val fd: FileDescriptor,
    private val spec: VideoSpec,
    private val sampleRate: Int,
    private val start: Long,
    private val length: Long,
    private val mix: OfflineMix,
    private val createCanvas: (Surface) -> CanvasRenderer,
) {
    private val info = MediaCodec.BufferInfo()
    private val floats = FloatArray(OfflineMix.MAX_BLOCK_FRAMES * 2)
    private val shorts = ShortArray(OfflineMix.MAX_BLOCK_FRAMES * 2)
    private var audioDone = 0L

    /** Encodes everything; [onProgress] gets the audio frames added since its last call. */
    suspend fun run(onProgress: (Long) -> Unit) {
        val muxer = MuxerSink(fd)
        var video: MediaCodec? = null
        var audio: MediaCodec? = null
        var input: Surface? = null
        var canvas: CanvasRenderer? = null
        var ok = false
        try {
            video = createVideoEncoder()
            input = video.createInputSurface()
            video.start()
            audio = createAudioEncoder()
            audio.start()
            canvas = createCanvas(input)

            val fps = spec.frameRate
            val videoDelayNs = AacDelay.frames(sampleRate) * 1_000_000_000L / sampleRate
            val frames = ceil(length.toDouble() * fps / sampleRate).toLong().coerceAtLeast(1)
            var reported = 0L
            for (k in 0 until frames) {
                coroutineContext.ensureActive()
                canvas.render(start + k.toDouble() * sampleRate / fps, k * 1_000_000_000L / fps + videoDelayNs)
                drain(video, muxer, isVideo = true, untilEos = false)
                val audioTarget = min(length, ceil((k + 1).toDouble() * sampleRate / fps).toLong())
                feedAudio(audio, muxer, audioTarget)
                onProgress(audioDone - reported)
                reported = audioDone
            }
            video.signalEndOfInputStream()
            feedAudio(audio, muxer, length)
            queueAudioEnd(audio, muxer)
            drain(video, muxer, isVideo = true, untilEos = true)
            drain(audio, muxer, isVideo = false, untilEos = true)
            onProgress(audioDone - reported)
            muxer.finish()
            ok = true
            Log.i(LOG_TAG, "export: video ${spec.width}x${spec.height}@$fps, $frames frame(s)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: VideoExportException) {
            throw e
        } catch (e: MediaCodec.CodecException) {
            throw VideoExportException("codec error ${e.diagnosticInfo}", e)
        } catch (e: IllegalStateException) {
            throw VideoExportException(e.message ?: "video encoding failed", e)
        } catch (e: IllegalArgumentException) {
            throw VideoExportException(e.message ?: "video encoding failed", e)
        } finally {
            runCatching { canvas?.close() }
            runCatching { video?.stop() }
            runCatching { video?.release() }
            runCatching { input?.release() }
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            if (!ok) muxer.abandon()
        }
    }

    private fun createVideoEncoder(): MediaCodec {
        val format = MediaFormat.createVideoFormat(spec.mimeType, spec.width, spec.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, spec.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val name = codecs.findEncoderForFormat(format)
            ?: throw VideoExportException("no ${spec.mimeType} encoder for ${spec.width}x${spec.height}@${spec.frameRate}")
        val caps = codecs.codecInfos.firstOrNull { it.name == name }?.getCapabilitiesForType(spec.mimeType)
        // A manual (or automatic 4K60) bitrate beyond what the encoder takes is clamped to its range.
        val bitRate = caps?.videoCapabilities?.bitrateRange?.clamp(spec.bitRate) ?: spec.bitRate
        if (bitRate != spec.bitRate) Log.i(LOG_TAG, "export: bitrate ${spec.bitRate} clamped to $bitRate")
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        // H.264 High profile when the encoder offers it (better quality per bit); otherwise its default.
        val high = caps?.profileLevels
            ?.filter { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
            ?.maxByOrNull { it.level }
        if (spec.mimeType == VideoSpec.MIME_AVC && high != null) {
            val withProfile = MediaFormat(format).apply {
                setInteger(MediaFormat.KEY_PROFILE, high.profile)
                setInteger(MediaFormat.KEY_LEVEL, high.level)
            }
            val codec = MediaCodec.createByCodecName(name)
            try {
                codec.configure(withProfile, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (e: RuntimeException) {
                Log.w(LOG_TAG, "export: $name refused High profile, using its default", e)
                codec.release()
            }
        }
        return MediaCodec.createByCodecName(name).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun createAudioEncoder(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, VideoSpec.AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, OfflineMix.MAX_BLOCK_FRAMES * 4)
        }
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: throw VideoExportException("no AAC encoder for $sampleRate Hz")
        return MediaCodec.createByCodecName(name).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    /** Renders and encodes the mix up to [target] frames (relative to the start). */
    private fun feedAudio(codec: MediaCodec, muxer: MuxerSink, target: Long) {
        while (audioDone < target) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(codec, muxer, isVideo = false, untilEos = false)
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: throw VideoExportException("no AAC input buffer")
            val frames = minOf((buffer.capacity() / 4).toLong(), OfflineMix.MAX_BLOCK_FRAMES.toLong(), target - audioDone).toInt()
            mix.renderMix(start + audioDone, frames, floats)
            for (i in 0 until frames * 2) {
                shorts[i] = (floats[i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            }
            buffer.clear()
            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(shorts, 0, frames * 2)
            codec.queueInputBuffer(index, 0, frames * 4, audioUs(audioDone), 0)
            audioDone += frames
            drain(codec, muxer, isVideo = false, untilEos = false)
        }
    }

    private fun queueAudioEnd(codec: MediaCodec, muxer: MuxerSink) {
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, audioUs(audioDone), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drain(codec, muxer, isVideo = false, untilEos = false)
        }
    }

    private fun audioUs(frame: Long): Long = frame * 1_000_000L / sampleRate

    /** Moves encoded output to the muxer: what is ready, or (with [untilEos]) everything. */
    private fun drain(codec: MediaCodec, muxer: MuxerSink, isVideo: Boolean, untilEos: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    if (++idle > MAX_IDLE_POLLS) throw VideoExportException("encoder did not finish")
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> muxer.addTrack(isVideo, codec.outputFormat)
                index >= 0 -> {
                    idle = 0
                    val buffer = codec.getOutputBuffer(index) ?: throw VideoExportException("no output buffer")
                    // Codec config (SPS/PPS, AudioSpecificConfig) reaches the muxer through the format.
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) muxer.write(isVideo, buffer, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** MediaMuxer that holds samples until both tracks are known (it cannot add tracks after start). */
    private class MuxerSink(fd: FileDescriptor) {
        private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var videoTrack = -1
        private var audioTrack = -1
        private var started = false
        private val waiting = ArrayList<Pair<Boolean, Pair<ByteArray, MediaCodec.BufferInfo>>>()

        fun addTrack(isVideo: Boolean, format: MediaFormat) {
            check(!started) { "track format changed after start" }
            if (isVideo) videoTrack = muxer.addTrack(format) else audioTrack = muxer.addTrack(format)
            if (videoTrack >= 0 && audioTrack >= 0) {
                muxer.start()
                started = true
                for ((video, sample) in waiting) {
                    muxer.writeSampleData(track(video), ByteBuffer.wrap(sample.first), sample.second)
                }
                waiting.clear()
            }
        }

        fun write(isVideo: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            if (started) {
                muxer.writeSampleData(track(isVideo), buffer, info)
            } else {
                val bytes = ByteArray(info.size).also { buffer.get(it) }
                val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                waiting += isVideo to (bytes to copy)
            }
        }

        fun finish() {
            check(started) { "no media was encoded" }
            muxer.stop()
            muxer.release()
        }

        fun abandon() {
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }

        private fun track(isVideo: Boolean) = if (isVideo) videoTrack else audioTrack
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        /** 5 s without output while finishing. */
        const val MAX_IDLE_POLLS = 500
    }
}
