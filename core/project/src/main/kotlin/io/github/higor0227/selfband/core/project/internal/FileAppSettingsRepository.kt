// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.higor0227.selfband.core.model.AppSettings
import io.github.higor0227.selfband.core.project.AppSettingsRepository
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
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class FileAppSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : AppSettingsRepository {

    private val store = AppSettingsStore(File(context.filesDir, "settings.json"))
    private val lock = Mutex()
    private var loaded = false
    private val state = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ensureLoaded() }
    }

    private suspend fun ensureLoaded() = lock.withLock {
        if (!loaded) {
            state.value = store.load()
            loaded = true
        }
    }

    override suspend fun get(): AppSettings = withContext(Dispatchers.IO) {
        ensureLoaded()
        state.value
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = withContext(Dispatchers.IO) {
        ensureLoaded()
        lock.withLock {
            val updated = transform(state.value).copy(schemaVersion = AppSettings.CURRENT_SCHEMA_VERSION)
            store.save(updated)
            state.value = updated
            updated
        }
    }
}

/** `<filesDir>/settings.json`. Pure JVM so it can be unit tested. */
internal class AppSettingsStore(private val file: File) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Defaults if the file is missing or unreadable. */
    fun load(): AppSettings {
        if (!file.exists()) return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), file.readText()) }
            .getOrDefault(AppSettings())
    }

    fun save(settings: AppSettings) {
        AtomicFiles.write(file, json.encodeToString(AppSettings.serializer(), settings))
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppSettingsModule {
    @Binds
    abstract fun bindRepository(impl: FileAppSettingsRepository): AppSettingsRepository
}
