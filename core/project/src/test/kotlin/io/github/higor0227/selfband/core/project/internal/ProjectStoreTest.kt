// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import io.github.higor0227.selfband.core.model.AlignmentInfo
import io.github.higor0227.selfband.core.model.AlignmentMethod
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.model.Take
import io.github.higor0227.selfband.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class ProjectStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun take(id: String, length: Long) = Take(
        id = id, audioFile = "$id.wav", channelCount = 1, lengthFrames = length,
        recordStartFrame = 0, autoOffsetFrames = -100,
        alignment = AlignmentInfo(AlignmentMethod.TIMESTAMPS, 100.0), createdAtMillis = 0,
    )

    private fun project(vararg tracks: Track) = Project(
        id = "p", name = "Song", createdAtMillis = 0, updatedAtMillis = 0, sampleRate = 48_000,
        tracks = tracks.toList(),
    )

    /** Writes a WAV like the engine does mid-recording: header still says 0 bytes. */
    private fun writeUnfinishedWav(file: File, frames: Int, extraBytes: Int = 0) {
        file.parentFile.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            WavFiles.writeHeader(raf, channels = 1, sampleRate = 48_000, dataBytes = 0)
            raf.seek(WavFiles.HEADER_SIZE)
            raf.write(ByteArray(frames * 4 + extraBytes))
        }
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val store = ProjectStore(tmp.root)
        val p = project(Track("t1", "#1", listOf(take("a", 480)), "a"))
        store.save(p)
        assertEquals(p, store.load("p"))
        assertFalse(File(tmp.root, "p/project.json.tmp").exists())
    }

    @Test
    fun interruptedTakeIsRecoveredFromFileSize() {
        val store = ProjectStore(tmp.root)
        store.save(project(Track("t1", "#1", listOf(take("a", Take.LENGTH_UNKNOWN)), "a")))
        val wav = File(store.trackDir("p", "t1"), "a.wav")
        writeUnfinishedWav(wav, frames = 1_000, extraBytes = 2) // partial trailing frame

        val loaded = store.load("p")
        assertNotNull(loaded)
        assertEquals(1_000L, loaded!!.tracks.single().takes.single().lengthFrames)
        assertEquals(1_000L, WavFiles.readInfo(wav)!!.frames)
        assertEquals(WavFiles.HEADER_SIZE + 4_000, wav.length())
        // The recovery is persisted.
        assertEquals(1_000L, ProjectStore(tmp.root).load("p")!!.tracks.single().takes.single().lengthFrames)
    }

    @Test
    fun interruptedTakeWithoutAudioIsDropped() {
        val store = ProjectStore(tmp.root)
        store.save(
            project(
                Track("t1", "#1", listOf(take("a", 480)), "a"),
                Track("t2", "#2", listOf(take("b", Take.LENGTH_UNKNOWN)), "b"),
            ),
        )
        val loaded = store.load("p")!!
        assertEquals(listOf("t1"), loaded.tracks.map { it.id })
    }

    @Test
    fun deleteRemovesEverything() {
        val store = ProjectStore(tmp.root)
        store.save(project())
        writeUnfinishedWav(File(store.trackDir("p", "t1"), "x.wav"), 10)
        store.delete("p")
        assertTrue(store.loadAll().isEmpty())
        assertFalse(store.projectDir("p").exists())
    }
}
