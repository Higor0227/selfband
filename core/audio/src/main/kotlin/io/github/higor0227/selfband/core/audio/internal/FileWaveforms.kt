// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio.internal

import android.util.Log
import io.github.higor0227.selfband.core.audio.Waveforms
import io.github.higor0227.selfband.core.audio.calibration.WavFloatReader
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Waveforms] with a cache file `<take>.wav.peaks` beside each take (takes never change after
 * recording; a newer WAV invalidates the cache) and a small in-memory cache.
 */
@Singleton
internal class FileWaveforms @Inject constructor() : Waveforms {
    private val lock = Mutex()
    private val memory = object : LinkedHashMap<String, Pair<Long, Waveform>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Waveform>>?) = size > MEMORY_ENTRIES
    }

    override suspend fun load(wav: File): Waveform? = withContext(Dispatchers.IO) {
        val modified = wav.lastModified()
        lock.withLock { memory[wav.path]?.takeIf { it.first == modified }?.second }?.let { return@withContext it }
        val cache = File(wav.path + Waveforms.CACHE_SUFFIX)
        val waveform = readCache(cache, modified) ?: compute(wav)?.also { writeCache(cache, it) }
        if (waveform != null) lock.withLock { memory[wav.path] = modified to waveform }
        waveform
    }

    private fun compute(wav: File): Waveform? {
        val started = System.nanoTime()
        val peaks = WavFloatReader.peaks(wav, Waveform.DEFAULT_FRAMES_PER_PEAK) ?: return null
        Log.i(LOG_TAG, "Waveform of ${wav.name}: ${peaks.size} peaks in ${(System.nanoTime() - started) / 1_000_000} ms")
        return Waveform(Waveform.DEFAULT_FRAMES_PER_PEAK, peaks)
    }

    private fun readCache(file: File, wavModified: Long): Waveform? = runCatching {
        if (!file.exists() || file.lastModified() < wavModified) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) return null
            val framesPerPeak = input.readInt()
            val count = input.readInt()
            val bytes = ByteArray(count * 4)
            input.readFully(bytes)
            val peaks = FloatArray(count)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(peaks)
            Waveform(framesPerPeak, peaks)
        }
    }.getOrNull()

    private fun writeCache(file: File, waveform: Waveform) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(waveform.framesPerPeak)
                out.writeInt(waveform.peaks.size)
                val bytes = ByteBuffer.allocate(waveform.peaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(waveform.peaks)
                out.write(bytes.array())
            }
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { Log.w(LOG_TAG, "Waveform cache not written: ${it.message}") }
    }

    private companion object {
        const val MAGIC = 0x53425046 // "SBPF"
        const val MEMORY_ENTRIES = 32
    }
}
