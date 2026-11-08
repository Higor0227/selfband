// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.calibration

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Reads a 32-bit float WAV (as written by the engine). */
internal object WavFloatReader {

    private class Layout(val channels: Int, val dataOffset: Long, val frames: Long)

    private fun layout(raf: RandomAccessFile): Layout? {
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
        return Layout(channels, dataOffset, dataSize / (4L * channels))
    }

    /** The whole file, one array per channel. For short files (calibration). */
    fun read(file: File): List<FloatArray>? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val l = layout(raf) ?: return null
            val frames = l.frames.toInt()
            val bytes = ByteArray(frames * 4 * l.channels)
            raf.seek(l.dataOffset)
            raf.readFully(bytes)
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            List(l.channels) { c -> FloatArray(frames) { i -> floats.get(i * l.channels + c) } }
        }
    }.getOrNull()

    /** Frame index of the loudest sample (any channel), streamed; null if unreadable or silent. */
    fun peakFrame(file: File): Long? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val l = layout(raf) ?: return null
            raf.seek(l.dataOffset)
            val block = ByteArray(4 * l.channels * BLOCK_FRAMES)
            var frame = 0L
            var best = 0f
            var bestFrame = -1L
            while (frame < l.frames) {
                val n = minOf(BLOCK_FRAMES.toLong(), l.frames - frame).toInt()
                raf.readFully(block, 0, n * 4 * l.channels)
                val floats = ByteBuffer.wrap(block, 0, n * 4 * l.channels).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until n * l.channels) {
                    val v = abs(floats.get(i))
                    if (v > best) {
                        best = v
                        bestFrame = frame + i / l.channels
                    }
                }
                frame += n
            }
            bestFrame.takeIf { it >= 0 }
        }
    }.getOrNull()

    /**
     * Absolute peak (loudest channel) of every block of [framesPerPeak] frames, streamed.
     * Null if the file is unreadable.
     */
    fun peaks(file: File, framesPerPeak: Int): FloatArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val l = layout(raf) ?: return null
            raf.seek(l.dataOffset)
            val out = FloatArray(((l.frames + framesPerPeak - 1) / framesPerPeak).toInt())
            val block = ByteArray(4 * l.channels * BLOCK_FRAMES)
            var frame = 0L
            while (frame < l.frames) {
                val n = minOf(BLOCK_FRAMES.toLong(), l.frames - frame).toInt()
                raf.readFully(block, 0, n * 4 * l.channels)
                val floats = ByteBuffer.wrap(block, 0, n * 4 * l.channels).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                for (i in 0 until n * l.channels) {
                    val v = abs(floats.get(i))
                    val bucket = ((frame + i / l.channels) / framesPerPeak).toInt()
                    if (v > out[bucket]) out[bucket] = v
                }
                frame += n
            }
            out
        }
    }.getOrNull()

    private const val BLOCK_FRAMES = 8192
}
