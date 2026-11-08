// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export.internal

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel

/**
 * Writes audio files into the shared Music collection (SPEC 2.7; Android only accepts audio
 * under Music, not Movies). Items stay pending (hidden) until [publish], and are deleted by
 * [discard] if the export fails or is cancelled.
 */
internal class MediaStoreAudio(private val resolver: ContentResolver) {

    class Item(val uri: Uri, val displayName: String)

    /** Creates a pending item in Music/Selfband/[folder]/ and passes [write] a seekable channel. */
    suspend fun create(folder: String, displayName: String, write: suspend (FileChannel) -> Unit): Item {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_WAV)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(folder))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore refused $displayName")
        val item = Item(uri, displayName)
        try {
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open $displayName")
            pfd.use { FileOutputStream(it.fileDescriptor).channel.use { channel -> write(channel) } }
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

        fun relativePath(folder: String) = "${Environment.DIRECTORY_MUSIC}/Selfband/$folder/"
    }
}
