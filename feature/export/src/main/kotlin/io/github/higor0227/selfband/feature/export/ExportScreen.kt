// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.export

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.higor0227.selfband.core.export.ExportFailure
import io.github.higor0227.selfband.core.export.ExportNames
import io.github.higor0227.selfband.core.export.ExportState
import io.github.higor0227.selfband.core.export.ExportStep
import io.github.higor0227.selfband.core.export.WavFormat
import io.github.higor0227.selfband.core.model.Project

@Composable
fun ExportRoute(onBack: () -> Unit, viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Notifications show the progress in the background; the export works without them too.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.start()
    }
    val context = LocalContext.current
    ExportScreen(
        state = state,
        onBack = onBack,
        video = VideoActions(
            onVideo = viewModel::setVideo,
            onCodec = viewModel::setCodec,
            onResolution = viewModel::setResolution,
            onFrameRate = viewModel::setFrameRate,
            onBitRate = viewModel::setBitRate,
        ),
        onMixWav = viewModel::setMixWav,
        onStems = viewModel::setStems,
        onFormat = viewModel::setFormat,
        onStart = {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.start()
            }
        },
        onCancel = viewModel::cancel,
        onReset = viewModel::reset,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportScreen(
    state: ExportUiState,
    onBack: () -> Unit,
    video: VideoActions,
    onMixWav: (Boolean) -> Unit,
    onStems: (Boolean) -> Unit,
    onFormat: (WavFormat) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_title) + (state.project?.name?.let { " · $it" } ?: "")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val project = state.project ?: return@Column
            if (project.lengthFrames <= 0) {
                Text(stringResource(R.string.export_nothing), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            RangeText(project)
            when (val export = state.export) {
                is ExportState.Running -> RunningCard(export, onCancel)
                is ExportState.Done -> DoneCard(export, onReset)
                is ExportState.Failed, is ExportState.Cancelled -> {
                    Text(
                        stringResource(
                            when (export) {
                                is ExportState.Failed -> when (export.reason) {
                                    ExportFailure.EMPTY_PROJECT -> R.string.export_failed_empty
                                    ExportFailure.UNREADABLE_TAKE -> R.string.export_failed_take
                                    ExportFailure.WRITE_FAILED -> R.string.export_failed_write
                                    ExportFailure.VIDEO_FAILED -> R.string.export_failed_video
                                }
                                else -> R.string.export_cancelled
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                    OptionsCard(state, video, onMixWav, onStems, onFormat, onStart)
                }
                else -> OptionsCard(state, video, onMixWav, onStems, onFormat, onStart)
            }
        }
    }
}

@Composable
private fun RangeText(project: Project) {
    val sr = project.sampleRate
    Text(
        stringResource(R.string.export_range, time(project.trimStart, sr), time(project.trimEnd, sr), time(project.trimEnd - project.trimStart, sr)) +
            if (project.isTrimmed) " · " + stringResource(R.string.export_range_trimmed) else "",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun OptionsCard(
    state: ExportUiState,
    video: VideoActions,
    onMixWav: (Boolean) -> Unit,
    onStems: (Boolean) -> Unit,
    onFormat: (WavFormat) -> Unit,
    onStart: () -> Unit,
) {
    val options = state.options
    val name = ExportNames.sanitize(state.project?.name.orEmpty())
    VideoCard(state, name, video)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.export_audio), style = MaterialTheme.typography.titleSmall)
            CheckRow(stringResource(R.string.export_mix_wav), stringResource(R.string.export_mix_wav_hint), options.mixWav, onMixWav)
            CheckRow(stringResource(R.string.export_stems), stringResource(R.string.export_stems_hint), options.stems, onStems)
            Text(stringResource(R.string.export_format), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = options.wavFormat == WavFormat.PCM_24, onClick = { onFormat(WavFormat.PCM_24) },
                    label = { Text(stringResource(R.string.export_24bit)) })
                FilterChip(selected = options.wavFormat == WavFormat.FLOAT_32, onClick = { onFormat(WavFormat.FLOAT_32) },
                    label = { Text(stringResource(R.string.export_32float)) })
            }
            Text(stringResource(R.string.export_destination, "Music/Selfband/$name/"), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (state.busyElsewhere) {
        Text(stringResource(R.string.export_other_project), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onStart, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.export_start))
    }
}

@Composable
internal fun CheckRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RunningCard(export: ExportState.Running, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (export.step) {
                    ExportStep.STEMS -> stringResource(R.string.export_running_stems, export.stemIndex, export.stemCount)
                    ExportStep.VIDEO -> if (export.passCount > 1) {
                        stringResource(R.string.export_running_video_pass, export.pass, export.passCount)
                    } else {
                        stringResource(R.string.export_running_video)
                    }
                    ExportStep.MIX -> stringResource(R.string.export_running_mix)
                },
            )
            LinearProgressIndicator(progress = { export.progress }, modifier = Modifier.fillMaxWidth())
            Text("${(export.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.export_background_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.export_cancel)) }
        }
    }
}

@Composable
private fun DoneCard(export: ExportState.Done, onReset: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.export_done, export.outputs.size, export.folders.joinToString(stringResource(R.string.export_and))), style = MaterialTheme.typography.bodyLarge)
            export.outputs.forEach { Text("• " + it.displayName, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { share(context, export) }) { Text(stringResource(R.string.export_share)) }
                OutlinedButton(onClick = onReset) { Text(stringResource(R.string.export_again)) }
            }
        }
    }
}

/** System share sheet with every exported file (read access granted to the receiver). */
private fun share(context: Context, export: ExportState.Done) {
    val uris = export.outputs.map { Uri.parse(it.uri) }
    if (uris.isEmpty()) return
    val mime = export.outputs.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = mime
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // ClipData carries the grant to every URI on all Android versions.
    intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun time(frames: Long, sampleRate: Int): String {
    val totalMs = frames * 1000 / sampleRate
    return "%d:%02d.%03d".format(totalMs / 60_000, (totalMs / 1000) % 60, totalMs % 1000)
}
