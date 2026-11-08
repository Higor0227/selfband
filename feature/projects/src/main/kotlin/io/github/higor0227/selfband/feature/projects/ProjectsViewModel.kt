// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.higor0227.selfband.core.audio.AudioDeviceMonitor
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.project.ProjectRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProjectsViewModel @Inject constructor(
    private val repository: ProjectRepository,
    private val deviceMonitor: AudioDeviceMonitor,
) : ViewModel() {

    val projects: StateFlow<List<Project>> = repository.projects

    /** Creates a project at the device's native sample rate (SPEC section 2.1). */
    fun create(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val sampleRate = deviceMonitor.systemInfo().nativeSampleRate ?: DEFAULT_SAMPLE_RATE
            val project = repository.create(name.trim(), sampleRate)
            onCreated(project.id)
        }
    }

    fun delete(projectId: String) {
        viewModelScope.launch { repository.delete(projectId) }
    }

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
    }
}
