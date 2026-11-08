// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.roundToInt

/** Sample format of exported WAV files (SPEC 2.7: stems in 24-bit or 32-bit float). */
enum class WavFormat(val bitsPerSample: Int) {
    PCM_24(24),
    FLOAT_32(32),
}

/**
 * Streaming WAV writer on a seekable channel (a MediaStore item opened "rw", or a file).
 * Canonical 44-byte header, written as a placeholder first and completed on [close].
 * 24-bit samples are clipped to ±1 and rounded (no dither: the mix is already limited to
 * −1 dBFS and 24 bits leave ~144 dB of range).
 */
class WavFileWriter(
    private val channel: FileChannel,
    private val channels: Int,
    private val sampleRate: Int,
    private val format: WavFormat,
) : Closeable {
    private val bytesPerSample = format.bitsPerSample / 8
    private val buffer = ByteBuffer.allocate(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var dataBytes = 0L
    private var closed = false

    init {
        require(channels in 1..8 && sampleRate > 0)
        channel.truncate(0)
        channel.position(0)
        writeFully(header(0))
    }

    /** Appends [count] interleaved samples from [samples]. */
    fun write(samples: FloatArray, count: Int) {
        check(!closed)
        require(count <= samples.size && count % channels == 0)
        for (i in 0 until count) {
            if (buffer.remaining() < bytesPerSample) flush()
            val x = samples[i]
            when (format) {
                WavFormat.FLOAT_32 -> buffer.putFloat(x)
                WavFormat.PCM_24 -> {
                    val v = (x.coerceIn(-1f, 1f) * PCM24_MAX).roundToInt()
                    buffer.put((v and 0xff).toByte())
                    buffer.put(((v shr 8) and 0xff).toByte())
                    buffer.put(((v shr 16) and 0xff).toByte())
                }
            }
        }
        dataBytes += count.toLong() * bytesPerSample
        if (dataBytes > MAX_DATA_BYTES) throw IOException("WAV larger than 4 GB")
    }

    val framesWritten: Long get() = dataBytes / (bytesPerSample * channels)

    /** Completes the header. The channel stays open (its owner closes it). */
    override fun close() {
        if (closed) return
        flush()
        channel.position(0)
        writeFully(header(dataBytes))
        channel.force(false)
        closed = true
    }

    private fun flush() {
        buffer.flip()
        while (buffer.hasRemaining()) channel.write(buffer)
        buffer.clear()
    }

    private fun writeFully(b: ByteBuffer) {
        while (b.hasRemaining()) channel.write(b)
    }

    private fun header(dataSize: Long): ByteBuffer {
        val blockAlign = channels * bytesPerSample
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataSize).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort((if (format == WavFormat.FLOAT_32) FORMAT_IEEE_FLOAT else FORMAT_PCM).toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(format.bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize.toInt())
            flip()
        }
    }

    companion object {
        const val HEADER_BYTES = 44
        private const val FORMAT_PCM = 1
        private const val FORMAT_IEEE_FLOAT = 3
        private const val PCM24_MAX = 8_388_607f
        private const val BUFFER_BYTES = 1 shl 16
        private const val MAX_DATA_BYTES = 0xFFFF_FFFFL - 36
    }
}
