// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project

import io.github.higor0227.selfband.core.model.DeviceProfile
import kotlinx.coroutines.flow.StateFlow

/** Saved per-route audio settings (SPEC section 2.4), stored in one JSON file. */
interface DeviceProfileRepository {
    /** All profiles, most recently updated first. */
    val profiles: StateFlow<List<DeviceProfile>>

    suspend fun get(key: String): DeviceProfile?

    /**
     * Applies [transform] to the profile with [key], creating it from [create] first if it
     * does not exist. Returns the saved profile.
     */
    suspend fun upsert(
        key: String,
        create: () -> DeviceProfile,
        transform: (DeviceProfile) -> DeviceProfile,
    ): DeviceProfile

    suspend fun delete(key: String)
}
