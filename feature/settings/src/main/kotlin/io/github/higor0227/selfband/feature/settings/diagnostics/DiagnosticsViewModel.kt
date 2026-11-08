// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.settings.diagnostics

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.higor0227.selfband.core.audio.AudioDeviceMonitor
import io.github.higor0227.selfband.core.audio.AudioProbe
import io.github.higor0227.selfband.core.audio.InputPreset
import io.github.higor0227.selfband.core.audio.ProbeRequest
import io.github.higor0227.selfband.core.audio.ProbeSnapshot
import io.github.higor0227.selfband.core.audio.ProbeStartResult
import io.github.higor0227.selfband.core.model.AudioDevice
import io.github.higor0227.selfband.core.model.AudioSystemInfo
import io.github.higor0227.selfband.core.model.DeviceProfile
import io.github.higor0227.selfband.core.project.DeviceProfileRepository
import io.github.higor0227.selfband.core.model.LOG_TAG
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProbeUiState(
    val running: Boolean = false,
    val withInput: Boolean = false,
    val inputPreset: InputPreset = InputPreset.VOICE_RECOGNITION,
    val snapshot: ProbeSnapshot? = null,
    val error: String? = null,
    val restarts: Int = 0,
)

data class DiagnosticsUiState(
    val system: AudioSystemInfo,
    val devices: List<AudioDevice>,
    val probe: ProbeUiState,
    val profiles: List<DeviceProfile> = emptyList(),
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val deviceMonitor: AudioDeviceMonitor,
    private val probe: AudioProbe,
    private val profileRepository: DeviceProfileRepository,
) : ViewModel() {

    private val systemInfo = deviceMonitor.systemInfo()

    // Detected capability decides the preset: unprocessed when the platform supports it.
    private val preferredPreset =
        if (systemInfo.supportsUnprocessedSource) InputPreset.UNPROCESSED else InputPreset.VOICE_RECOGNITION

    private val probeState = MutableStateFlow(ProbeUiState(inputPreset = preferredPreset))
    private var pollJob: Job? = null

    val uiState: StateFlow<DiagnosticsUiState> =
        combine(deviceMonitor.devices, probeState, profileRepository.profiles) { devices, probe, profiles ->
            DiagnosticsUiState(systemInfo, devices, probe, profiles)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DiagnosticsUiState(systemInfo, deviceMonitor.devices.value, probeState.value),
        )

    fun deleteProfile(key: String) {
        viewModelScope.launch { profileRepository.delete(key) }
    }

    fun setIncludeInput(include: Boolean) {
        probeState.update { it.copy(withInput = include) }
        if (probeState.value.running) start()
    }

    fun toggleProbe() {
        if (probeState.value.running) stop() else start()
    }

    private fun currentRequest() = probeState.value.let {
        ProbeRequest(withInput = it.withInput, inputPreset = it.inputPreset)
    }

    private fun start() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            when (val result = probe.start(currentRequest())) {
                is ProbeStartResult.Failed -> {
                    probeState.update { it.copy(running = false, error = result.reason, snapshot = null) }
                    return@launch
                }
                ProbeStartResult.Started ->
                    probeState.update { it.copy(running = true, error = null) }
            }
            while (isActive) {
                val snapshot = probe.snapshot()
                probeState.update { it.copy(snapshot = snapshot) }
                // A stream closed by the system (e.g. device unplugged): reopen on the new route.
                if (snapshot.output.disconnected || snapshot.input.disconnected) {
                    Log.i(LOG_TAG, "Probe stream disconnected; restarting on the current route")
                    probeState.update { it.copy(restarts = it.restarts + 1) }
                    if (probe.start(currentRequest()) is ProbeStartResult.Failed) {
                        probeState.update { it.copy(running = false) }
                        break
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stop() {
        pollJob?.cancel()
        pollJob = null
        viewModelScope.launch {
            probe.stop()
            probeState.update { it.copy(running = false) }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        // viewModelScope is already cancelled here, but the probe is an app singleton and its
        // streams must not outlive the screen.
        MainScope().launch { probe.stop() }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
    }
}
