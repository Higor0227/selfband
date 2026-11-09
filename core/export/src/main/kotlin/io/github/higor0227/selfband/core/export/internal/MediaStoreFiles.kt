// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export.internal

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import io.github.higor0227.selfband.core.model.ExportFolder
import java.io.IOException

/**
 * Writes exported files into the shared collections (SPEC 2.7): video under Movies/<folder>,
 * audio under Music/<folder> (Android accepts audio only under Music); the folder is
 * "Selfband" unless changed in the settings. Items stay pending
 * (hidden) until [publish], and are deleted by [discard] if the export fails or is cancelled.
 */
internal class MediaStoreFiles(private val resolver: ContentResolver, val root: String = ExportFolder.DEFAULT) {

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
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(kind, subfolder, root))
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

    /** Makes [items] visible. Returns them with the names Android settled on ("name (1).mp4" on a clash). */
    fun publish(items: List<Item>): List<Item> {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        return items.map { item ->
            resolver.update(item.uri, values, null, null)
            val actual = runCatching {
                resolver.query(item.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
            if (actual != null && actual != item.displayName) Item(item.uri, actual, item.mimeType) else item
        }
    }

    fun discard(items: List<Item>) {
        items.forEach { runCatching { resolver.delete(it.uri, null, null) } }
    }

    companion object {
        const val MIME_WAV = "audio/x-wav"
        const val MIME_MP4 = "video/mp4"

        /** e.g. "Movies/Selfband/" or "Music/Selfband/My song/" ([root] is the export folder, SPEC 2.8). */
        fun relativePath(kind: Kind, subfolder: String?, root: String = ExportFolder.DEFAULT): String =
            "${kind.directory}/$root/" + (subfolder?.let { "$it/" } ?: "")
    }
}
