// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video

import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import io.github.higor0227.selfband.core.model.LOG_TAG
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** More videos need one decoder than it allows at once: [needed] against [available]. */
data class DecoderLimit(val needed: Int, val available: Int)

/**
 * Simultaneous decoders (SPEC 3.5), from what the codecs declare
 * (`MediaCodecInfo.CodecCapabilities.getMaxSupportedInstances()`), never per device.
 */
object DecoderInstances {
    private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    /** Decoder name and its instance limit per file (files are never rewritten). */
    private val perFile = ConcurrentHashMap<String, Pair<String, Int>>()

    /**
     * The decoder most short of instances for decoding all [files] at once, or null if they
     * all fit. Missing or unreadable files are skipped. Reads the files: call off the main thread.
     */
    fun shortfall(files: List<File>): DecoderLimit? {
        val perDecoder = HashMap<String, Int>()
        val instances = HashMap<String, Int>()
        for (file in files) {
            val (name, max) = decoderOf(file) ?: continue
            if (name.isEmpty()) return DecoderLimit(files.size, 0)
            perDecoder[name] = (perDecoder[name] ?: 0) + 1
            instances[name] = max
        }
        return perDecoder.entries
            .map { (name, needed) -> DecoderLimit(needed, instances.getValue(name)) }
            .filter { it.needed > it.available }
            .maxByOrNull { it.needed - it.available }
    }

    /** Empty name: no decoder takes this format. */
    private fun decoderOf(file: File): Pair<String, Int>? {
        perFile[file.absolutePath]?.let { return it }
        val format = videoFormat(file) ?: return null
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        format.removeKey(MediaFormat.KEY_FRAME_RATE)
        val name = codecs.findDecoderForFormat(format) ?: return "" to 0
        val max = codecs.codecInfos.firstOrNull { it.name == name }
            ?.getCapabilitiesForType(mime)?.maxSupportedInstances ?: Int.MAX_VALUE
        return (name to max).also { perFile[file.absolutePath] = it }
    }

    private fun videoFormat(file: File): MediaFormat? {
        if (!file.exists()) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "cannot read ${file.name}", e)
            null
        } finally {
            extractor.release()
        }
    }
}
