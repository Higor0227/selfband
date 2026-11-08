// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.model.Take
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * File layout (SPEC section 3.6):
 * ```
 * <root>/<projectId>/project.json
 * <root>/<projectId>/tracks/<trackId>/<takeId>.wav
 * ```
 * Pure JVM (no Android types) so it can be unit tested.
 */
internal class ProjectStore(private val root: File) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun projectDir(id: String) = File(root, id)
    fun projectFile(id: String) = File(projectDir(id), PROJECT_FILE)
    fun trackDir(projectId: String, trackId: String) = File(projectDir(projectId), "tracks/$trackId")

    fun loadAll(): List<Project> =
        root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir -> load(dir.name) }

    /** Loads, migrates and recovers a project. Returns null if unreadable. */
    fun load(id: String): Project? {
        val file = projectFile(id)
        if (!file.exists()) return null
        return runCatching {
            val element = json.parseToJsonElement(file.readText()) as JsonObject
            val version = element["schemaVersion"]?.jsonPrimitive?.int ?: 1
            val migrated = Migrations.migrate(element, version)
            val project = json.decodeFromJsonElement(Project.serializer(), migrated)
            val recovered = recoverInterruptedTakes(project)
            if (recovered != project || version != Project.CURRENT_SCHEMA_VERSION) save(recovered)
            recovered
        }.getOrNull()
    }

    /** Atomic write: temp file + fsync + rename (SPEC section 3.6). */
    fun save(project: Project) {
        AtomicFiles.write(projectFile(project.id), json.encodeToString(Project.serializer(), project))
    }

    fun delete(id: String) {
        projectDir(id).deleteRecursively()
    }

    /**
     * A take whose length is unknown was being recorded when the app died. Recover what
     * reached the disk (fixing the WAV header); drop it if nothing usable was written.
     */
    private fun recoverInterruptedTakes(project: Project): Project {
        if (project.tracks.none { t -> t.takes.any { it.lengthFrames == Take.LENGTH_UNKNOWN } }) return project
        val tracks = project.tracks.map { track ->
            val takes = track.takes.mapNotNull { take ->
                if (take.lengthFrames != Take.LENGTH_UNKNOWN) return@mapNotNull take
                val file = File(trackDir(project.id, track.id), take.audioFile)
                val frames = WavFiles.repairHeader(file)
                if (frames != null && frames > 0) take.copy(lengthFrames = frames) else {
                    file.delete()
                    null
                }
            }
            track.copy(takes = takes)
        }.filter { it.takes.isNotEmpty() }
        return project.copy(tracks = tracks)
    }

    companion object {
        const val PROJECT_FILE = "project.json"
    }
}

/** Schema migrations, applied on the raw JSON before decoding. */
internal object Migrations {
    fun migrate(element: JsonObject, fromVersion: Int): JsonObject {
        require(fromVersion <= Project.CURRENT_SCHEMA_VERSION) {
            "Project schema $fromVersion is newer than this app supports"
        }
        // No migrations yet: schema 1 is the first version.
        return element
    }
}
