// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import android.content.Context
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.LayoutTemplate
import io.github.higor0227.selfband.core.model.LayoutTemplates
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.project.LayoutTemplateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class FileLayoutTemplateRepository @Inject constructor(
    @ApplicationContext context: Context,
) : LayoutTemplateRepository {

    private val store = LayoutTemplateStore(File(context.filesDir, "layout-templates.json"))
    private val lock = Mutex()
    private var loaded = false
    private val state = MutableStateFlow<List<LayoutTemplate>>(emptyList())
    override val templates: StateFlow<List<LayoutTemplate>> = state.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { lock.withLock { ensureLoaded() } }
    }

    /** Call with [lock] held. */
    private fun ensureLoaded() {
        if (!loaded) {
            state.value = store.load()
            loaded = true
        }
    }

    private suspend fun edit(transform: (List<LayoutTemplate>) -> List<LayoutTemplate>) = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoaded()
            val updated = transform(state.value)
            store.save(updated)
            state.value = updated
        }
    }

    override suspend fun saveFrom(project: Project, name: String): LayoutTemplate? {
        val template = LayoutTemplates.fromProject(LayoutTemplateStore.newId(), name, project) ?: return null
        edit { it + template }
        Log.i(LOG_TAG, "Layout template saved: ${template.id} \"${template.name}\" with ${template.slotsFor(project.aspectRatio).size} slots")
        return template
    }

    override suspend fun rename(id: String, name: String) {
        val clean = name.trim().take(LayoutTemplates.MAX_NAME_LENGTH)
        if (clean.isEmpty()) return
        edit { list -> list.map { if (it.id == id) it.copy(name = clean) else it } }
    }

    override suspend fun delete(id: String) {
        edit { list -> list.filterNot { it.id == id } }
        Log.i(LOG_TAG, "Layout template deleted: $id")
    }
}

/** `<filesDir>/layout-templates.json`, in the built-in templates' format. Pure JVM so it can be unit tested. */
internal class LayoutTemplateStore(private val file: File) {

    /** Empty if the file is missing or unreadable. */
    fun load(): List<LayoutTemplate> {
        if (!file.exists()) return emptyList()
        return runCatching { LayoutTemplates.parse(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(templates: List<LayoutTemplate>) {
        AtomicFiles.write(file, LayoutTemplates.encode(templates))
    }

    companion object {
        fun newId(): String = "user-" + UUID.randomUUID().toString()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LayoutTemplateModule {
    @Binds
    abstract fun bindRepository(impl: FileLayoutTemplateRepository): LayoutTemplateRepository
}
