// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import io.github.higor0227.selfband.core.export.VideoSpec
import io.github.higor0227.selfband.core.model.LOG_TAG
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Measures the AAC encoder's delay (priming) in frames: a click encoded at frame N comes out of
 * a decoder at frame N + delay. MP4 players play those leading frames, so the export shifts the
 * video by the same amount to keep picture and sound together (SPEC 1: measured, never assumed
 * per device). The result is cached per sample rate; 0 if the measurement fails.
 */
internal object AacDelay {
    private val cache = HashMap<Int, Int>()

    @Synchronized
    fun frames(sampleRate: Int): Int = cache.getOrPut(sampleRate) {
        try {
            measure(sampleRate).also { Log.i(LOG_TAG, "export: AAC delay $it frame(s) at $sampleRate Hz") }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "export: AAC delay measurement failed, assuming 0", e)
            0
        }
    }

    private fun measure(sampleRate: Int): Int {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, VideoSpec.AUDIO_BIT_RATE)
        }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoderName = checkNotNull(codecs.findEncoderForFormat(format)) { "no AAC encoder" }
        val packets = ArrayList<Pair<ByteArray, Long>>()
        lateinit var outFormat: MediaFormat

        // Encode silence with one click at CLICK_AT, the same settings as the export.
        val encoder = MediaCodec.createByCodecName(encoderName)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputDone = false
            var done = false
            var polls = 0
            while (!done) {
                check(++polls < MAX_POLLS) { "encoder stalled" }
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = encoder.getInputBuffer(index)!!
                        buffer.clear()
                        val frames = minOf(buffer.capacity() / 4, TOTAL_FRAMES - fed)
                        if (frames <= 0) {
                            encoder.queueInputBuffer(index, 0, 0, usOf(fed, sampleRate), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            for (f in fed until fed + frames) {
                                val v: Short = if (f == CLICK_AT) CLICK else 0
                                shorts.put(v)
                                shorts.put(v)
                            }
                            encoder.queueInputBuffer(index, 0, frames * 4, usOf(fed, sampleRate), 0)
                            fed += frames
                        }
                    }
                }
                val out = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = encoder.outputFormat
                if (out >= 0) {
                    val buffer = encoder.getOutputBuffer(out)!!
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buffer.position(info.offset)
                        packets += ByteArray(info.size).also { buffer.get(it) } to info.presentationTimeUs
                    }
                    encoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }

        // Decode it (the encoder's output format carries the AudioSpecificConfig) and find the click.
        val decoderName = checkNotNull(codecs.findDecoderForFormat(outFormat.withoutRate())) { "no AAC decoder" }
        val decoder = MediaCodec.createByCodecName(decoderName)
        try {
            decoder.configure(outFormat, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var next = 0
            var inputDone = false
            var frame = 0
            var peakAt = -1
            var peak = 0
            var polls = 0
            while (true) {
                check(++polls < MAX_POLLS) { "decoder stalled" }
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        if (next < packets.size) {
                            val (bytes, us) = packets[next++]
                            decoder.getInputBuffer(index)!!.apply { clear(); put(bytes) }
                            decoder.queueInputBuffer(index, 0, bytes.size, us, 0)
                        } else {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (out >= 0) {
                    val channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val shorts = decoder.getOutputBuffer(out)!!.apply { position(info.offset); limit(info.offset + info.size) }
                        .order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    for (f in 0 until frames) {
                        val v = abs(shorts.get(f * channels).toInt())
                        if (v > peak) {
                            peak = v
                            peakAt = frame + f
                        }
                    }
                    frame += frames
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            check(peakAt >= 0 && peak > CLICK / 8) { "click not found (peak $peak)" }
            return (peakAt - CLICK_AT).coerceIn(0, MAX_DELAY)
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
    }

    private fun MediaFormat.withoutRate() = MediaFormat(this).apply { removeKey(MediaFormat.KEY_BIT_RATE) }

    private fun usOf(frame: Int, sampleRate: Int) = frame * 1_000_000L / sampleRate

    private const val CLICK_AT = 4096
    private const val TOTAL_FRAMES = 16384
    private const val CLICK: Short = 24_000
    private const val MAX_DELAY = 8192
    private const val TIMEOUT_US = 5_000L
    private const val MAX_POLLS = 2_000
}
