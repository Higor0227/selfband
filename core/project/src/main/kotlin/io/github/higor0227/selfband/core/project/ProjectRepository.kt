// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project

import io.github.higor0227.selfband.core.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Project persistence: versioned `project.json` + media files per project directory. */
interface ProjectRepository {
    /** All projects, most recently updated first. */
    val projects: StateFlow<List<Project>>

    fun observe(projectId: String): Flow<Project?>

    suspend fun create(name: String, sampleRate: Int): Project

    /** Applies [transform] and persists atomically. Returns the saved project. */
    suspend fun update(projectId: String, transform: (Project) -> Project): Project

    suspend fun delete(projectId: String)

    /** Where a take's audio file lives (directories are created). */
    fun takeAudioFile(projectId: String, trackId: String, fileName: String): File

    /** Free space on the volume that holds projects, in bytes. */
    fun freeBytes(): Long
}
