// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV helpers for files written by the native engine: canonical 44-byte header,
 * IEEE float 32-bit (format 3). Used to recover takes after a crash, when the header sizes
 * were not finalized.
 */
internal object WavFiles {
    const val HEADER_SIZE = 44L
    private const val BYTES_PER_SAMPLE = 4

    data class Info(val channelCount: Int, val sampleRate: Int, val frames: Long)

    fun readInfo(file: File): Info? {
        if (!file.exists() || file.length() < HEADER_SIZE) return null
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(HEADER_SIZE.toInt())
            raf.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null
            val channels = buf.getShort(22).toInt()
            val sampleRate = buf.getInt(24)
            if (channels <= 0) return null
            val dataBytes = file.length() - HEADER_SIZE
            return Info(channels, sampleRate, dataBytes / (channels * BYTES_PER_SAMPLE))
        }
    }

    /**
     * Rewrites the RIFF and data chunk sizes from the actual file length (dropping a
     * trailing partial frame). Returns the number of complete frames, or null if the file
     * is not a WAV we wrote.
     */
    fun repairHeader(file: File): Long? {
        val info = readInfo(file) ?: return null
        val frameBytes = info.channelCount * BYTES_PER_SAMPLE
        val dataBytes = info.frames * frameBytes
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(HEADER_SIZE + dataBytes)
            val sizes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            raf.seek(4)
            raf.write(sizes.putInt(0, clampToUInt32(36 + dataBytes)).array())
            raf.seek(40)
            raf.write(sizes.putInt(0, clampToUInt32(dataBytes)).array())
            raf.fd.sync()
        }
        return info.frames
    }

    /** Writes a canonical header for float32 data (used by tests). */
    fun writeHeader(raf: RandomAccessFile, channels: Int, sampleRate: Int, dataBytes: Long) {
        val b = ByteBuffer.allocate(HEADER_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(clampToUInt32(36 + dataBytes)).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(channels.toShort())
        b.putInt(sampleRate).putInt(sampleRate * channels * BYTES_PER_SAMPLE)
        b.putShort((channels * BYTES_PER_SAMPLE).toShort()).putShort(32)
        b.put("data".toByteArray()).putInt(clampToUInt32(dataBytes))
        raf.seek(0)
        raf.write(b.array())
    }

    // RIFF sizes are unsigned 32-bit; files over 4 GiB need RF64 (not supported yet).
    private fun clampToUInt32(v: Long): Int = v.coerceAtMost(0xFFFFFFFFL).toInt()
}
