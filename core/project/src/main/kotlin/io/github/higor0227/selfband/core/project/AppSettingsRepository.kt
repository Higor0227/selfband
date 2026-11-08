// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project

import io.github.higor0227.selfband.core.model.AppSettings
import kotlinx.coroutines.flow.StateFlow

/** App-wide settings (SPEC 2.8), stored in one JSON file. */
interface AppSettingsRepository {
    /** Current settings; defaults until the file has been read. */
    val settings: StateFlow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings
}
