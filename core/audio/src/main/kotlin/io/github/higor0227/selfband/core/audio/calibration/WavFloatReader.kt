// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.calibration

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a 32-bit float WAV (as written by the engine) into one array per channel. */
internal object WavFloatReader {

    fun read(file: File): List<FloatArray>? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return null
            var channels = 0
            var bits = 0
            var format = 0
            var dataOffset = -1L
            var dataSize = 0L
            val chunk = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunk)
                val id = String(chunk, 0, 4)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                val start = raf.filePointer
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(16)
                        raf.readFully(fmt)
                        val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        format = b.getShort(0).toInt() and 0xffff
                        channels = b.getShort(2).toInt()
                        bits = b.getShort(14).toInt()
                    }
                    "data" -> {
                        dataOffset = start
                        // The size may be stale if the take was cut short: trust the file length.
                        dataSize = minOf(if (size == 0L) Long.MAX_VALUE else size, raf.length() - start)
                        break
                    }
                }
                raf.seek(start + size + (size and 1))
            }
            val isFloat = format == 3 || format == 0xFFFE
            if (dataOffset < 0 || channels <= 0 || bits != 32 || !isFloat) return null
            val frames = (dataSize / (4L * channels)).toInt()
            val bytes = ByteArray(frames * 4 * channels)
            raf.seek(dataOffset)
            raf.readFully(bytes)
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            List(channels) { c -> FloatArray(frames) { i -> floats.get(i * channels + c) } }
        }
    }.getOrNull()
}
