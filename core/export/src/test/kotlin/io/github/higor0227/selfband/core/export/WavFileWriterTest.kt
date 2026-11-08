// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {

    private fun write(format: WavFormat, channels: Int, samples: FloatArray, blocks: Int = 1): ByteBuffer {
        val file = File.createTempFile("wav", ".wav")
        try {
            RandomAccessFile(file, "rw").use { raf ->
                WavFileWriter(raf.channel, channels, 48_000, format).use { w ->
                    repeat(blocks) { w.write(samples, samples.size) }
                }
            }
            return ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        } finally {
            file.delete()
        }
    }

    private fun ByteBuffer.tag(at: Int) = String(ByteArray(4) { get(at + it) }, Charsets.US_ASCII)

    @Test
    fun pcm24HeaderAndSamples() {
        val b = write(WavFormat.PCM_24, 2, floatArrayOf(0f, 0.5f, -1f, 1.5f))
        assertEquals("RIFF", b.tag(0))
        assertEquals("WAVE", b.tag(8))
        assertEquals("fmt ", b.tag(12))
        assertEquals(1, b.getShort(20).toInt())          // PCM
        assertEquals(2, b.getShort(22).toInt())          // channels
        assertEquals(48_000, b.getInt(24))
        assertEquals(48_000 * 6, b.getInt(28))           // byte rate
        assertEquals(6, b.getShort(32).toInt())          // block align
        assertEquals(24, b.getShort(34).toInt())
        assertEquals("data", b.tag(36))
        assertEquals(12, b.getInt(40))
        assertEquals(36 + 12, b.getInt(4))
        assertEquals(44 + 12, b.limit())
        fun s24(i: Int): Int {
            val o = 44 + i * 3
            val v = (b.get(o).toInt() and 0xff) or ((b.get(o + 1).toInt() and 0xff) shl 8) or (b.get(o + 2).toInt() shl 16)
            return v
        }
        assertEquals(0, s24(0))
        assertEquals(4_194_304, s24(1))                  // 0.5 rounded
        assertEquals(-8_388_607, s24(2))
        assertEquals(8_388_607, s24(3))                  // clipped
    }

    @Test
    fun float32KeepsValuesAndCountsManyBlocks() {
        val samples = FloatArray(30_000) { (it % 7) / 7f - 0.5f }
        val b = write(WavFormat.FLOAT_32, 1, samples, blocks = 5)
        assertEquals(3, b.getShort(20).toInt())          // IEEE float
        assertEquals(32, b.getShort(34).toInt())
        assertEquals(30_000 * 5 * 4, b.getInt(40))
        assertEquals(samples[12_345], b.getFloat(44 + (30_000 * 2 + 12_345) * 4), 0f)
    }

    @Test
    fun namesAreSafeForFiles() {
        assertEquals("Minha m_sica_ v2", ExportNames.sanitize("Minha m/sica: v2"))
        assertEquals("Selfband", ExportNames.sanitize("  ... "))
        assertEquals(60, ExportNames.sanitize("x".repeat(100)).length)
        assertEquals("Ação", ExportNames.sanitize("Ação"))
    }
}
