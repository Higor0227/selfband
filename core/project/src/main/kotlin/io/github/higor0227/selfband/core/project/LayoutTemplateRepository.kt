// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.project

import io.github.higor0227.selfband.core.model.LayoutTemplate
import io.github.higor0227.selfband.core.model.Project
import kotlinx.coroutines.flow.StateFlow

/** Layout templates saved by the user (SPEC 2.6), app-wide, stored as JSON like the built-in ones. */
interface LayoutTemplateRepository {
    /** Saved templates, oldest first. */
    val templates: StateFlow<List<LayoutTemplate>>

    /** Saves the layout [project] shows now under [name]; null if it has no visible layer. */
    suspend fun saveFrom(project: Project, name: String): LayoutTemplate?

    suspend fun rename(id: String, name: String)

    suspend fun delete(id: String)
}
