// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export.internal

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.IOException

/**
 * Writes exported files into the shared collections (SPEC 2.7): video under Movies/Selfband,
 * audio under Music/Selfband (Android accepts audio only under Music). Items stay pending
 * (hidden) until [publish], and are deleted by [discard] if the export fails or is cancelled.
 */
internal class MediaStoreFiles(private val resolver: ContentResolver) {

    enum class Kind(val directory: String) {
        VIDEO(Environment.DIRECTORY_MOVIES),
        AUDIO(Environment.DIRECTORY_MUSIC),
    }

    class Item(val uri: Uri, val displayName: String, val mimeType: String)

    /**
     * Creates a pending item in [relativePath] and passes [write] a seekable ("rw") descriptor,
     * closed afterwards.
     */
    suspend fun create(
        kind: Kind,
        subfolder: String?,
        displayName: String,
        mimeType: String,
        write: suspend (ParcelFileDescriptor) -> Unit,
    ): Item {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(kind, subfolder))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = when (kind) {
            Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $displayName")
        val item = Item(uri, displayName, mimeType)
        try {
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open $displayName")
            pfd.use { write(it) }
        } catch (t: Throwable) {
            discard(listOf(item))
            throw t
        }
        return item
    }

    fun publish(items: List<Item>) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        items.forEach { resolver.update(it.uri, values, null, null) }
    }

    fun discard(items: List<Item>) {
        items.forEach { runCatching { resolver.delete(it.uri, null, null) } }
    }

    companion object {
        const val MIME_WAV = "audio/x-wav"
        const val MIME_MP4 = "video/mp4"

        /** e.g. "Movies/Selfband/" or "Music/Selfband/My song/". */
        fun relativePath(kind: Kind, subfolder: String?): String =
            "${kind.directory}/Selfband/" + (subfolder?.let { "$it/" } ?: "")
    }
}
