// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.higor0227.selfband.core.camera.LockSupport
import io.github.higor0227.selfband.core.model.VideoResolution

/** Callbacks for the camera settings card. */
data class CameraSettingsActions(
    val onResolution: (VideoResolution) -> Unit,
    val onFrameRate: (Int) -> Unit,
    val onStabilization: (Boolean) -> Unit,
    val onGrid: (Boolean) -> Unit,
    val onToggleLock: (CameraLockKind) -> Unit,
    val onGhostTrack: (String?) -> Unit,
    val onPreviewGhostOpacity: (Float) -> Unit,
    val onSetGhostOpacity: (Float) -> Unit,
)

/** Format, stabilization, grid, AE/AWB/AF locks and ghost (SPEC 2.2). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraSettingsCard(state: RecorderUiState, ghostOpacity: Float, actions: CameraSettingsActions) {
    if (!state.videoEnabled || !state.cameraPermission) return
    val caps = state.capabilities
    val settings = state.cameraSettings
    val locks = settings.locksFor(settings.lens)
    val enabled = state.isIdle && state.camera.isOpen
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.camera_settings), style = MaterialTheme.typography.titleSmall)

            // Format: only what the camera and the encoder support.
            val formats = caps?.formats.orEmpty()
            val currentResolution = state.camera.videoSize?.let { size ->
                VideoResolution.entries.firstOrNull { it.width == size.width && it.height == size.height }
            }
            if (formats.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    formats.keys.sortedBy { it.width }.forEach { res ->
                        FilterChip(
                            selected = res == currentResolution,
                            onClick = { actions.onResolution(res) },
                            enabled = enabled,
                            label = { Text(res.label()) },
                        )
                    }
                }
                val rates = currentResolution?.let { formats[it] }.orEmpty()
                if (rates.size > 1) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rates.forEach { fps ->
                            FilterChip(
                                selected = fps == state.camera.frameRate,
                                onClick = { actions.onFrameRate(fps) },
                                enabled = enabled,
                                label = { Text("$fps fps") },
                            )
                        }
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = settings.grid,
                    onClick = { actions.onGrid(!settings.grid) },
                    label = { Text(stringResource(R.string.grid)) },
                )
                if (caps?.stabilization == true) {
                    FilterChip(
                        selected = settings.stabilization,
                        onClick = { actions.onStabilization(!settings.stabilization) },
                        enabled = enabled,
                        label = { Text(stringResource(R.string.stabilization)) },
                    )
                }
            }

            // Locks: tap to keep the current value; tap again for automatic.
            val lockChips = listOfNotNull(
                Triple(CameraLockKind.EXPOSURE, locks.exposure != null, caps?.exposureLock),
                Triple(CameraLockKind.WHITE_BALANCE, locks.whiteBalance != null, caps?.whiteBalanceLock),
                Triple(CameraLockKind.FOCUS, locks.focus != null, caps?.focusLock),
            ).filter { it.third != null && it.third != LockSupport.NONE }
            if (lockChips.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lockChips.forEach { (kind, locked, support) ->
                        FilterChip(
                            selected = locked,
                            onClick = { actions.onToggleLock(kind) },
                            enabled = enabled,
                            label = {
                                Text(stringResource(kind.labelRes()) + if (support == LockSupport.SESSION) " *" else "")
                            },
                        )
                    }
                }
                Text(stringResource(R.string.locks_hint), style = MaterialTheme.typography.bodySmall)
                if (lockChips.any { it.third == LockSupport.SESSION }) {
                    Text(stringResource(R.string.locks_session_hint), style = MaterialTheme.typography.bodySmall)
                }
            }

            GhostPicker(state, ghostOpacity, actions)
        }
    }
}

@Composable
private fun GhostPicker(state: RecorderUiState, ghostOpacity: Float, actions: CameraSettingsActions) {
    val settings = state.cameraSettings
    val tracks = state.project?.tracks.orEmpty()
    val candidates = tracks.withIndex().filter { t -> t.value.takes.any { it.video != null } }
    if (candidates.isEmpty()) return
    var menu by remember { mutableStateOf(false) }
    val chosenIndex = tracks.indexOfFirst { it.id == settings.ghostTrackId }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ghost), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { menu = true }) {
                Text(
                    if (chosenIndex >= 0) stringResource(R.string.track_n, chosenIndex + 1)
                    else stringResource(R.string.ghost_none),
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ghost_none)) },
                    onClick = { menu = false; actions.onGhostTrack(null) },
                )
                candidates.forEach { (index, track) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.track_n, index + 1)) },
                        onClick = { menu = false; actions.onGhostTrack(track.id) },
                    )
                }
            }
        }
    }
    if (settings.ghostTrackId != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ghost_opacity), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = ghostOpacity,
                onValueChange = actions.onPreviewGhostOpacity,
                onValueChangeFinished = { actions.onSetGhostOpacity(ghostOpacity) },
                valueRange = 0.05f..0.95f,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
        Text(stringResource(R.string.ghost_hint), style = MaterialTheme.typography.bodySmall)
    }
}

private fun VideoResolution.label(): String = when (this) {
    VideoResolution.P720 -> "720p"
    VideoResolution.P1080 -> "1080p"
    VideoResolution.P2160 -> "4K"
}

private fun CameraLockKind.labelRes(): Int = when (this) {
    CameraLockKind.EXPOSURE -> R.string.lock_exposure
    CameraLockKind.WHITE_BALANCE -> R.string.lock_white_balance
    CameraLockKind.FOCUS -> R.string.lock_focus
}
