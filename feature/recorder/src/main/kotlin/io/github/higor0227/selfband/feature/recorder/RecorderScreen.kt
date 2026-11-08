// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.higor0227.selfband.core.audio.AudioApi
import io.github.higor0227.selfband.core.audio.RecordChannelMode
import io.github.higor0227.selfband.core.audio.TransportState
import io.github.higor0227.selfband.core.model.AlignmentMath
import io.github.higor0227.selfband.core.model.Levels
import io.github.higor0227.selfband.core.model.MetronomeSettings
import io.github.higor0227.selfband.core.model.Track
import io.github.higor0227.selfband.core.ui.component.LevelMeter
import java.util.Locale

@Composable
fun RecorderRoute(onBack: () -> Unit, viewModel: RecorderViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO) }

    // The engine runs only while the screen is visible. Going to the background stops and
    // saves the current take (SPEC section 5: interrupted takes are kept).
    if (granted) {
        LifecycleStartEffect(Unit) {
            viewModel.onStart()
            onStopOrDispose { viewModel.onStop() }
        }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RecorderScreen(
        state = state,
        permissionGranted = granted,
        onBack = onBack,
        onTogglePlay = viewModel::togglePlay,
        onRecord = viewModel::recordNewTrack,
        onStop = viewModel::stop,
        onToggleMute = viewModel::toggleMute,
        onPreviewVolume = viewModel::previewTrackVolume,
        onSetVolume = viewModel::setTrackVolume,
        onSetManualOffset = viewModel::setManualOffsetMs,
        onDeleteTrack = viewModel::deleteTrack,
        onUpdateMetronome = viewModel::updateMetronome,
        onTapTempo = viewModel::tapTempo,
        onChannelMode = viewModel::setChannelMode,
        onDismissNotice = viewModel::dismissNotice,
        device = DeviceActions(
            onPreviewInputGain = viewModel::previewInputGain,
            onSetInputGain = viewModel::setInputGain,
            onMonitoring = viewModel::setMonitoring,
            onPreviewMonitorGain = viewModel::previewMonitorGain,
            onSetMonitorGain = viewModel::setMonitorGain,
            onCalibrate = viewModel::openCalibration,
            onStartCalibration = viewModel::startCalibration,
            onCancelCalibration = viewModel::cancelCalibration,
            onSaveCalibration = viewModel::saveCalibration,
            onCloseCalibration = viewModel::closeCalibration,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    state: RecorderUiState,
    permissionGranted: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onToggleMute: (String) -> Unit,
    onPreviewVolume: (String, Double) -> Unit,
    onSetVolume: (String, Double) -> Unit,
    onSetManualOffset: (String, Double) -> Unit,
    onDeleteTrack: (String) -> Unit,
    onUpdateMetronome: ((MetronomeSettings) -> MetronomeSettings) -> Unit,
    onTapTempo: (Long) -> Unit,
    onChannelMode: (RecordChannelMode) -> Unit,
    onDismissNotice: () -> Unit,
    device: DeviceActions,
) {
    val recording = state.transport == TransportState.RECORDING
    KeepScreenOnAndLockRotation(recording)

    val snackbar = remember { SnackbarHostState() }
    val noticeText = state.notice?.let { notice ->
        if (notice == RecorderNotice.DEVICE_CHANGED) {
            stringResource(
                R.string.notice_device_changed,
                io.github.higor0227.selfband.core.model.DeviceProfile.deviceLabel(state.route?.output),
                stringResource(if (state.profile?.calibration != null) R.string.profile_loaded else R.string.profile_not_calibrated),
            )
        } else {
            stringResource(notice.textRes())
        }
    }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            onDismissNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.project?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        CalibrationDialog(
            ui = state.calibration,
            onStart = device.onStartCalibration,
            onCancel = device.onCancelCalibration,
            onSave = device.onSaveCalibration,
            onClose = device.onCloseCalibration,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                when {
                    !permissionGranted -> ErrorText(stringResource(R.string.mic_permission_needed))
                    state.engineError != null -> ErrorText(stringResource(R.string.engine_error, state.engineError))
                }
            }
            item {
                DeviceCard(
                    state = state,
                    onChannelMode = onChannelMode,
                    onPreviewInputGain = device.onPreviewInputGain,
                    onSetInputGain = device.onSetInputGain,
                    onMonitoring = device.onMonitoring,
                    onPreviewMonitorGain = device.onPreviewMonitorGain,
                    onSetMonitorGain = device.onSetMonitorGain,
                    onCalibrate = device.onCalibrate,
                )
            }
            item { TransportCard(state, onTogglePlay, onRecord, onStop) }
            state.project?.let { project ->
                item { MetronomeCard(project.metronome, onUpdateMetronome, onTapTempo) }
            }
            val tracks = state.project?.tracks.orEmpty()
            if (tracks.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_tracks), style = MaterialTheme.typography.bodyMedium)
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackCard(
                    index = index,
                    track = track,
                    sampleRate = state.project?.sampleRate ?: 48_000,
                    isRecording = recording && index == tracks.lastIndex,
                    canEdit = state.isIdle,
                    peak = state.trackPeak(track.id),
                    onToggleMute = { onToggleMute(track.id) },
                    onPreviewVolume = { onPreviewVolume(track.id, it) },
                    onSetVolume = { onSetVolume(track.id, it) },
                    onSetManualOffset = { onSetManualOffset(track.id, it) },
                    onDelete = { onDeleteTrack(track.id) },
                )
            }
            item { EngineInfo(state) }
        }
    }
}

@Composable
private fun TransportCard(
    state: RecorderUiState,
    onTogglePlay: () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
) {
    val s = state.status
    val sampleRate = state.project?.sampleRate ?: 48_000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val metronome = state.project?.metronome ?: MetronomeSettings()
            val active = state.transport != TransportState.IDLE
            val inCountIn = active && s.positionFrames < 0
            val framesPerBeat = metronome.framesPerBeat(sampleRate)
            val beat = metronome.beatAt(s.positionFrames, sampleRate)
            val beatInBar = Math.floorMod(beat, metronome.beatsPerBar.toLong()).toInt()
            val phase = s.positionFrames - beat * framesPerBeat
            // Visual click: lit for the first quarter of each beat (status refreshes every 50 ms).
            val flash = metronome.visualFlash && active && (metronome.enabled || inCountIn) &&
                phase < framesPerBeat / 4
            val flashColor = when {
                !flash -> Color.Transparent
                beatInBar == 0 && metronome.accentFirstBeat -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    formatTime(s.positionFrames, sampleRate),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Box(Modifier.width(28.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(flashColor))
            }
            if (inCountIn) {
                Text(
                    stringResource(R.string.counting_in, beatInBar + 1, metronome.beatsPerBar),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.transport) {
                    TransportState.IDLE -> {
                        FilledTonalButton(onClick = onTogglePlay, enabled = s.isOpen) {
                            Text(stringResource(R.string.play))
                        }
                        Button(
                            onClick = onRecord,
                            enabled = s.isOpen,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text(stringResource(R.string.record_new_track)) }
                    }
                    else -> Button(onClick = onStop) { Text(stringResource(R.string.stop)) }
                }
            }
            val bytesPerSecond = sampleRate.toLong() * 4 * if (state.channelMode == RecordChannelMode.STEREO && s.inputChannels >= 2) 2 else 1
            val remainingSec = if (bytesPerSecond > 0) state.freeBytes / bytesPerSecond else 0
            Text(
                stringResource(
                    R.string.free_space,
                    String.format(Locale.getDefault(), "%.1f", state.freeBytes / 1e9),
                    remainingSec / 3600, (remainingSec % 3600) / 60,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TrackCard(
    index: Int,
    track: Track,
    sampleRate: Int,
    isRecording: Boolean,
    canEdit: Boolean,
    peak: Float,
    onToggleMute: () -> Unit,
    onPreviewVolume: (Double) -> Unit,
    onSetVolume: (Double) -> Unit,
    onSetManualOffset: (Double) -> Unit,
    onDelete: () -> Unit,
) {
    val take = track.activeTake
    var offsetDialog by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
      Column {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.track_n, index + 1), style = MaterialTheme.typography.titleSmall)
                if (isRecording || take == null || take.lengthFrames < 0) {
                    Text(stringResource(R.string.recording_now), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    val offsetMs = AlignmentMath.framesToMs(take.autoOffsetFrames.toDouble(), sampleRate)
                    Text(
                        formatTime(take.lengthFrames, sampleRate) + " · " +
                            stringResource(R.string.alignment_value, String.format(Locale.ROOT, "%+.1f", offsetMs),
                                take.alignment.method.name.lowercase()) +
                            " · " + stringResource(if (take.channelCount == 2) R.string.stereo else R.string.mono),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (take.manualOffsetFrames != 0L) {
                        val manualMs = AlignmentMath.framesToMs(take.manualOffsetFrames.toDouble(), sampleRate)
                        Text(
                            stringResource(R.string.manual_offset_value, String.format(Locale.ROOT, "%+.1f", manualMs)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (take.alignment.xrunsDuringTake > 0 || take.alignment.droppedFrames > 0) {
                        Text(stringResource(R.string.take_glitched_short), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(stringResource(R.string.audible), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(4.dp))
            Switch(checked = !track.muted, onCheckedChange = { onToggleMute() })
            IconButton(onClick = { offsetDialog = true }, enabled = canEdit && take != null && take.lengthFrames >= 0) {
                Icon(Icons.Filled.Edit, stringResource(R.string.adjust_offset))
            }
            IconButton(onClick = onDelete, enabled = canEdit) {
                Icon(Icons.Filled.Delete, stringResource(R.string.delete_track))
            }
        }
        // Volume fader: live while dragging, saved on release. Tap the value to reset to 0 dB.
        var volumeDraft by remember(track.volumeDb) { mutableFloatStateOf(track.volumeDb.toFloat()) }
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Slider(
                    value = volumeDraft,
                    onValueChange = { volumeDraft = it; onPreviewVolume(it.toDouble()) },
                    onValueChangeFinished = { onSetVolume(volumeDraft.toDouble()) },
                    valueRange = Levels.MIN_DB.toFloat()..Levels.MAX_DB.toFloat(),
                )
                LevelMeter(peak)
            }
            TextButton(onClick = { volumeDraft = 0f; onSetVolume(0.0) }) {
                Text(
                    if (volumeDraft <= Levels.MIN_DB) "\u2212\u221E dB"
                    else String.format(Locale.ROOT, "%+.1f dB", volumeDraft),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
      }
    }
    if (offsetDialog && take != null) {
        ManualOffsetDialog(
            initialMs = AlignmentMath.framesToMs(take.manualOffsetFrames.toDouble(), sampleRate),
            onDismiss = { offsetDialog = false },
            onConfirm = { offsetDialog = false; onSetManualOffset(it) },
        )
    }
}

@Composable
private fun ManualOffsetDialog(initialMs: Double, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var text by remember { mutableStateOf(String.format(Locale.ROOT, "%.1f", initialMs)) }
    val value = text.replace(',', '.').toDoubleOrNull()?.takeIf { kotlin.math.abs(it) <= MAX_MANUAL_OFFSET_MS }
    fun nudge(delta: Double) {
        val v = ((value ?: 0.0) + delta).coerceIn(-MAX_MANUAL_OFFSET_MS, MAX_MANUAL_OFFSET_MS)
        text = String.format(Locale.ROOT, "%.1f", v)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adjust_offset)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.adjust_offset_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(8) },
                    singleLine = true,
                    isError = value == null,
                    label = { Text(stringResource(R.string.offset_ms)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(-10.0, -1.0, 1.0, 10.0).forEach { d ->
                        OutlinedButton(onClick = { nudge(d) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(String.format(Locale.ROOT, "%+d", d.toInt()))
                        }
                    }
                }
                TextButton(onClick = { text = "0.0" }) { Text(stringResource(R.string.reset)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value!!) }, enabled = value != null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private const val MAX_MANUAL_OFFSET_MS = 2_000.0

@Composable
private fun EngineInfo(state: RecorderUiState) {
    val s = state.status
    if (!s.isOpen) return
    val api = { a: AudioApi -> if (a == AudioApi.AAUDIO) "AAudio" else if (a == AudioApi.OPENSL_ES) "OpenSL ES" else "?" }
    val bufferMs = if (s.sampleRate > 0) s.outputBufferFrames * 1000.0 / s.sampleRate else 0.0
    val lines = listOf(
        "${s.sampleRate} Hz · out ${api(s.outputApi)}${if (s.outputMmap) " MMAP" else ""} · " +
            "in ${api(s.inputApi)}${if (s.inputMmap) " MMAP" else ""} · ${s.inputChannels} ch",
        String.format(Locale.ROOT, "buffer %.1f ms · latency est. out %s / in %s", bufferMs,
            s.outputLatencyMs?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "?",
            s.inputLatencyMs?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "?"),
        "xruns out ${s.outputXruns ?: "?"} / in ${s.inputXruns ?: "?"} · underruns ${s.underruns} · dropped ${s.droppedFrames}",
    )
    Column {
        Spacer(Modifier.height(8.dp))
        lines.forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

/** Keeps the screen on and the orientation fixed while recording (SPEC section 2.2). */
@Composable
private fun KeepScreenOnAndLockRotation(active: Boolean) {
    val view = LocalView.current
    val activity = LocalActivity.current
    DisposableEffect(active) {
        if (active) {
            view.keepScreenOn = true
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }
        onDispose {
            view.keepScreenOn = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

private fun RecorderNotice.textRes() = when (this) {
    RecorderNotice.DEVICE_CHANGED -> R.string.notice_device_changed // shown with arguments, see above
    RecorderNotice.TAKE_INTERRUPTED -> R.string.notice_take_interrupted
    RecorderNotice.TAKE_GLITCHED -> R.string.notice_take_glitched
    RecorderNotice.RECORD_FAILED -> R.string.notice_record_failed
}

/** m:ss.mmm; negative during count-in. */
private fun formatTime(frames: Long, sampleRate: Int): String {
    val sign = if (frames < 0) "-" else ""
    val totalMs = kotlin.math.abs(frames) * 1000 / sampleRate.coerceAtLeast(1)
    return sign + String.format(Locale.ROOT, "%d:%02d.%03d", totalMs / 60_000, (totalMs / 1000) % 60, totalMs % 1000)
}

/** Callbacks for the device card and the calibration dialog. */
data class DeviceActions(
    val onPreviewInputGain: (Double) -> Unit,
    val onSetInputGain: (Double) -> Unit,
    val onMonitoring: (Boolean) -> Unit,
    val onPreviewMonitorGain: (Double) -> Unit,
    val onSetMonitorGain: (Double) -> Unit,
    val onCalibrate: () -> Unit,
    val onStartCalibration: (CalibrationTask) -> Unit,
    val onCancelCalibration: () -> Unit,
    val onSaveCalibration: () -> Unit,
    val onCloseCalibration: () -> Unit,
)
