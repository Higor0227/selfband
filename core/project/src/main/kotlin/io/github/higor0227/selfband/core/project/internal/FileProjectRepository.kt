// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import android.content.Context
import android.os.StatFs
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.project.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class FileProjectRepository @Inject constructor(
    @ApplicationContext context: Context,
) : ProjectRepository {

    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val store = ProjectStore(root)
    private val writeLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = MutableStateFlow<Map<String, Project>>(emptyMap())

    override val projects: StateFlow<List<Project>> = cache
        .map { map -> map.values.sortedByDescending { it.updatedAtMillis } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            val loaded = store.loadAll()
            Log.i(LOG_TAG, "Loaded ${loaded.size} project(s)")
            cache.update { current -> loaded.associateBy { it.id } + current }
        }
    }

    override fun observe(projectId: String): Flow<Project?> =
        cache.map { it[projectId] }.distinctUntilChanged()

    override suspend fun create(name: String, sampleRate: Int): Project = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAtMillis = now,
            updatedAtMillis = now,
            sampleRate = sampleRate,
        )
        writeLock.withLock {
            store.save(project)
            cache.update { it + (project.id to project) }
        }
        Log.i(LOG_TAG, "Project created: ${project.id} @ $sampleRate Hz")
        project
    }

    override suspend fun update(projectId: String, transform: (Project) -> Project): Project =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                val current = cache.value[projectId] ?: store.load(projectId)
                    ?: error("Project $projectId not found")
                val updated = transform(current).copy(updatedAtMillis = System.currentTimeMillis())
                store.save(updated)
                cache.update { it + (projectId to updated) }
                updated
            }
        }

    override suspend fun delete(projectId: String): Unit = withContext(Dispatchers.IO) {
        writeLock.withLock {
            store.delete(projectId)
            cache.update { it - projectId }
        }
        Log.i(LOG_TAG, "Project deleted: $projectId")
    }

    override fun takeAudioFile(projectId: String, trackId: String, fileName: String): File =
        File(store.trackDir(projectId, trackId).apply { mkdirs() }, fileName)

    override fun freeBytes(): Long = StatFs(root.path).availableBytes
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProjectModule {
    @Binds
    abstract fun bindRepository(impl: FileProjectRepository): ProjectRepository
}
