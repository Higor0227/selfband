// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project.internal

import android.content.Context
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.higor0227.selfband.core.model.DeviceProfile
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.project.DeviceProfileRepository
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class FileDeviceProfileRepository @Inject constructor(
    @ApplicationContext context: Context,
) : DeviceProfileRepository {

    private val store = DeviceProfileStore(File(context.filesDir, "device_profiles.json"))
    private val lock = Mutex()
    private val loaded = MutableStateFlow(false)
    private val state = MutableStateFlow<List<DeviceProfile>>(emptyList())
    override val profiles: StateFlow<List<DeviceProfile>> = state.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ensureLoaded() }
    }

    private suspend fun ensureLoaded() = lock.withLock {
        if (!loaded.value) {
            state.value = store.load().sortedByDescending { it.updatedAtMillis }
            loaded.value = true
            Log.i(LOG_TAG, "Loaded ${state.value.size} device profile(s)")
        }
    }

    override suspend fun get(key: String): DeviceProfile? = withContext(Dispatchers.IO) {
        ensureLoaded()
        state.value.firstOrNull { it.key == key }
    }

    override suspend fun upsert(
        key: String,
        create: () -> DeviceProfile,
        transform: (DeviceProfile) -> DeviceProfile,
    ): DeviceProfile = withContext(Dispatchers.IO) {
        ensureLoaded()
        lock.withLock {
            val current = state.value.firstOrNull { it.key == key } ?: create()
            val updated = transform(current).copy(key = key, updatedAtMillis = System.currentTimeMillis())
            val list = listOf(updated) + state.value.filterNot { it.key == key }
            store.save(list)
            state.value = list
            updated
        }
    }

    override suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        ensureLoaded()
        lock.withLock {
            val list = state.value.filterNot { it.key == key }
            store.save(list)
            state.value = list
        }
        Log.i(LOG_TAG, "Device profile deleted: $key")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DeviceProfileModule {
    @Binds
    abstract fun bindRepository(impl: FileDeviceProfileRepository): DeviceProfileRepository
}
