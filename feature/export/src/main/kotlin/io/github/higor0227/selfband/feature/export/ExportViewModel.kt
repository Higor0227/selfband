// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.export

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.higor0227.selfband.core.export.ExportManager
import io.github.higor0227.selfband.core.export.ExportOptions
import io.github.higor0227.selfband.core.export.ExportState
import io.github.higor0227.selfband.core.export.WavFormat
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.project.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ExportUiState(
    val project: Project? = null,
    val options: ExportOptions = ExportOptions(),
    /** The export of this project (running or finished), or null. */
    val export: ExportState? = null,
    /** True while another project is being exported. */
    val busyElsewhere: Boolean = false,
) {
    val canStart: Boolean
        get() = project != null && project.trimEnd > project.trimStart && !options.isEmpty &&
            export !is ExportState.Running && !busyElsewhere
}

@HiltViewModel
class ExportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: ProjectRepository,
    private val manager: ExportManager,
) : ViewModel() {

    private val projectId: String = checkNotNull(savedStateHandle[ARG_PROJECT_ID])
    private val options = MutableStateFlow(ExportOptions())

    val uiState: StateFlow<ExportUiState> =
        combine(repository.observe(projectId), options, manager.state) { project, opts, export ->
            val mine = export.takeIf { it.projectId() == projectId }
            ExportUiState(
                project = project,
                options = opts,
                export = mine,
                busyElsewhere = export is ExportState.Running && mine == null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState())

    fun setVideo(on: Boolean) = options.update { it.copy(video = on) }
    fun setMixWav(on: Boolean) = options.update { it.copy(mixWav = on) }
    fun setStems(on: Boolean) = options.update { it.copy(stems = on) }
    fun setFormat(format: WavFormat) = options.update { it.copy(wavFormat = format) }

    fun start() {
        if (uiState.value.canStart) manager.start(projectId, options.value)
    }

    fun cancel() = manager.cancel()

    /** Back to the options after a finished export. */
    fun reset() = manager.dismiss()

    companion object {
        const val ARG_PROJECT_ID = "projectId"
    }
}

private fun ExportState.projectId(): String? = when (this) {
    ExportState.Idle -> null
    is ExportState.Running -> projectId
    is ExportState.Done -> projectId
    is ExportState.Failed -> projectId
    is ExportState.Cancelled -> projectId
}
