// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.layout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import io.github.higor0227.selfband.core.model.LOG_TAG
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Brings a picked picture into the project as the canvas background (SPEC 2.6). */
internal object BackgroundImages {
    /** Longest side kept: enough for a 1080p/4K-ish canvas, small enough to decode quickly. */
    private const val MAX_SIDE = 2048
    private const val JPEG_QUALITY = 90

    /**
     * Decodes [uri] upright (EXIF orientation applied), scales it down to [MAX_SIDE] and writes
     * it as JPEG to [dest]. The project keeps its own copy, so the original may be deleted.
     * Blocking; returns false if the picture cannot be read.
     */
    fun import(context: Context, uri: Uri, dest: File): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = MAX_SIDE.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bitmap.recycle()
            tmp.renameTo(dest).also { if (!it) tmp.delete() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "background image import failed", e)
            tmp.delete()
            false
        }
    }
}
