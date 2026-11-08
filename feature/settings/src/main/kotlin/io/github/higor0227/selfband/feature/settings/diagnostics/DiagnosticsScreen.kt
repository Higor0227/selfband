// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.settings.diagnostics

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.higor0227.selfband.core.audio.AudioApi
import io.github.higor0227.selfband.core.audio.StreamStats
import io.github.higor0227.selfband.core.model.AudioDevice
import io.github.higor0227.selfband.core.ui.component.KeyValueRow
import io.github.higor0227.selfband.core.ui.component.LevelMeter
import io.github.higor0227.selfband.core.ui.component.SectionHeader
import io.github.higor0227.selfband.feature.settings.R
import java.util.Locale

@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        if (granted) viewModel.setIncludeInput(true)
    }

    DiagnosticsScreen(
        state = state,
        permissionDenied = permissionDenied,
        onBack = onBack,
        onToggleProbe = viewModel::toggleProbe,
        onIncludeInputChange = { include ->
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (include && !granted) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                viewModel.setIncludeInput(include)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    permissionDenied: Boolean,
    onBack: () -> Unit,
    onToggleProbe: () -> Unit,
    onIncludeInputChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item { ProbeSection(state, permissionDenied, onToggleProbe, onIncludeInputChange) }
            item { SystemSection(state) }

            val outputs = state.devices.filter { !it.isInput }
            val inputs = state.devices.filter { it.isInput }
            item { SectionHeader(stringResource(R.string.section_outputs, outputs.size)) }
            items(outputs, key = { "out-${it.id}" }) { DeviceCard(it) }
            item { SectionHeader(stringResource(R.string.section_inputs, inputs.size)) }
            items(inputs, key = { "in-${it.id}" }) { DeviceCard(it) }
        }
    }
}

@Composable
private fun SystemSection(state: DiagnosticsUiState) {
    val s = state.system
    val unknown = stringResource(R.string.unknown)
    SectionHeader(stringResource(R.string.section_system))
    KeyValueRow(stringResource(R.string.native_sample_rate), s.nativeSampleRate?.let { "$it Hz" } ?: unknown)
    KeyValueRow(
        stringResource(R.string.native_buffer),
        s.nativeFramesPerBuffer?.let { frames ->
            val ms = s.nativeSampleRate?.let { sr -> " (" + fmtMs(frames * 1000.0 / sr) + ")" }.orEmpty()
            stringResource(R.string.frames_value, frames) + ms
        } ?: unknown,
    )
    KeyValueRow(stringResource(R.string.low_latency_feature), yesNo(s.hasLowLatencyFeature))
    KeyValueRow(stringResource(R.string.pro_audio_feature), yesNo(s.hasProAudioFeature))
    KeyValueRow(stringResource(R.string.unprocessed_source), yesNo(s.supportsUnprocessedSource))
}

@Composable
private fun ProbeSection(
    state: DiagnosticsUiState,
    permissionDenied: Boolean,
    onToggleProbe: () -> Unit,
    onIncludeInputChange: (Boolean) -> Unit,
) {
    val probe = state.probe
    SectionHeader(stringResource(R.string.section_probe))
    Text(stringResource(R.string.probe_explainer), style = MaterialTheme.typography.bodySmall)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.probe_include_input), modifier = Modifier.weight(1f))
        Switch(checked = probe.withInput, onCheckedChange = onIncludeInputChange)
    }
    if (permissionDenied) {
        Text(
            stringResource(R.string.permission_denied),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = onToggleProbe, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (probe.running) R.string.probe_stop else R.string.probe_start))
    }
    probe.error?.let {
        Text(
            stringResource(R.string.start_failed, it),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (probe.restarts > 0) {
        Text(
            stringResource(R.string.probe_restarts, probe.restarts),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    val snapshot = probe.snapshot
    if (probe.running && snapshot != null) {
        StreamCard(stringResource(R.string.stream_output), snapshot.output, state.devices, showLevel = false)
        if (probe.withInput) {
            KeyValueRow(stringResource(R.string.input_preset), probe.inputPreset.name)
            StreamCard(stringResource(R.string.stream_input), snapshot.input, state.devices, showLevel = true)
        }
    }
}

@Composable
private fun StreamCard(title: String, stats: StreamStats, devices: List<AudioDevice>, showLevel: Boolean) {
    val unknown = stringResource(R.string.unknown)
    Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!stats.isOpen) {
                Text(stringResource(R.string.not_open), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val device = devices.firstOrNull { it.id == stats.deviceId }
            KeyValueRow(
                stringResource(R.string.device),
                device?.let { "${it.type} #${it.id}" } ?: "#${stats.deviceId}",
            )
            KeyValueRow(
                stringResource(R.string.api),
                when (stats.api) {
                    AudioApi.AAUDIO -> "AAudio"
                    AudioApi.OPENSL_ES -> "OpenSL ES"
                    AudioApi.UNSPECIFIED -> unknown
                },
            )
            KeyValueRow(stringResource(R.string.mmap), stats.mmap?.let { yesNo(it) } ?: unknown)
            KeyValueRow(stringResource(R.string.sharing), stats.sharingMode.name)
            KeyValueRow(stringResource(R.string.performance), stats.performanceMode.name)
            KeyValueRow(stringResource(R.string.sample_rate), "${stats.sampleRate} Hz")
            KeyValueRow(stringResource(R.string.channels), stats.channelCount.toString())
            KeyValueRow(stringResource(R.string.format), stats.format.name)
            KeyValueRow(stringResource(R.string.burst), stringResource(R.string.frames_value, stats.framesPerBurst))
            KeyValueRow(
                stringResource(R.string.buffer),
                stringResource(R.string.frames_value, stats.bufferSizeFrames) + " (" + fmtMs(stats.bufferSizeMs) + ")",
            )
            KeyValueRow(stringResource(R.string.capacity), stringResource(R.string.frames_value, stats.bufferCapacityFrames))
            KeyValueRow(stringResource(R.string.latency_estimate), stats.latencyMs?.let(::fmtMs) ?: unknown)
            KeyValueRow(stringResource(R.string.xruns), stats.xruns?.toString() ?: unknown)
            KeyValueRow(stringResource(R.string.callbacks), stats.callbacks.toString())
            if (showLevel) {
                Text(
                    stringResource(R.string.level),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                LevelMeter(stats.peak)
            }
        }
    }
}

@Composable
private fun DeviceCard(device: AudioDevice) {
    val any = stringResource(R.string.any)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${device.type}  #${device.id}",
                style = MaterialTheme.typography.titleSmall,
            )
            if (device.name.isNotBlank()) {
                Text(device.name, style = MaterialTheme.typography.bodySmall)
            }
            KeyValueRow(
                stringResource(R.string.sample_rates),
                device.sampleRates.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: any,
            )
            KeyValueRow(
                stringResource(R.string.channels),
                device.channelCounts.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: any,
            )
        }
    }
}

@Composable
private fun yesNo(value: Boolean) = stringResource(if (value) R.string.yes else R.string.no)

private fun fmtMs(ms: Double) = String.format(Locale.ROOT, "%.1f ms", ms)
