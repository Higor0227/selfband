// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.higor0227.selfband.core.model.Levels
import io.github.higor0227.selfband.core.model.Mixing
import io.github.higor0227.selfband.core.model.Track
import io.github.higor0227.selfband.core.ui.component.LevelMeter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** Callbacks for the mixer card. Preview = live while dragging; set = saved on release. */
data class MixerActions(
    val onToggleMute: (String) -> Unit,
    val onToggleSolo: (String) -> Unit,
    val onPreviewVolume: (String, Double) -> Unit,
    val onSetVolume: (String, Double) -> Unit,
    val onPreviewPan: (String, Float) -> Unit,
    val onSetPan: (String, Float) -> Unit,
    val onPreviewMaster: (Double) -> Unit,
    val onSetMaster: (Double) -> Unit,
    val onLimiter: (Boolean) -> Unit,
    val onReset: () -> Unit,
)

/**
 * Basic mixer (SPEC 2.5): per track volume, pan, mute and solo with a meter, then the master
 * volume and the clipping protection. Everything is heard at once, also during playback.
 */
@Composable
fun MixerCard(state: RecorderUiState, actions: MixerActions) {
    val project = state.project ?: return
    // The engine meters hold their last value while stopped; show silence instead.
    val live = !state.isIdle
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mixer), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = actions.onReset) { Text(stringResource(R.string.mixer_reset)) }
            }
            if (project.tracks.isEmpty()) {
                Text(stringResource(R.string.no_tracks), style = MaterialTheme.typography.bodyMedium)
            }
            val anySolo = Mixing.anySolo(project)
            project.tracks.forEachIndexed { index, track ->
                MixerTrackRow(
                    index = index,
                    track = track,
                    silencedBySolo = anySolo && !track.solo && !track.muted,
                    peak = if (live) state.trackPeak(track.id) else 0f,
                    actions = actions,
                )
                HorizontalDivider()
            }
            MasterSection(state, live, actions)
        }
    }
}

@Composable
private fun MixerTrackRow(
    index: Int,
    track: Track,
    silencedBySolo: Boolean,
    peak: Float,
    actions: MixerActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.track_n, index + 1), style = MaterialTheme.typography.labelLarge)
                if (silencedBySolo) {
                    Text(stringResource(R.string.mixer_silenced_by_solo), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilterChip(
                selected = track.muted,
                onClick = { actions.onToggleMute(track.id) },
                label = { Text(stringResource(R.string.mixer_mute_short)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            )
            FilterChip(
                selected = track.solo,
                onClick = { actions.onToggleSolo(track.id) },
                label = { Text(stringResource(R.string.mixer_solo_short)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            )
        }
        LevelMeter(peak)
        // Volume: tap the value to go back to 0 dB.
        var volume by remember(track.volumeDb) { mutableFloatStateOf(track.volumeDb.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mixer_volume), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(48.dp))
            Slider(
                value = volume,
                onValueChange = { volume = it; actions.onPreviewVolume(track.id, it.toDouble()) },
                onValueChangeFinished = { actions.onSetVolume(track.id, volume.toDouble()) },
                valueRange = Levels.MIN_DB.toFloat()..Levels.MAX_DB.toFloat(),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { volume = 0f; actions.onSetVolume(track.id, 0.0) }) {
                MonoValue(formatDb(volume, Levels.MIN_DB))
            }
        }
        // Pan: tap the value to center.
        var pan by remember(track.pan) { mutableFloatStateOf(track.pan) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mixer_pan), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(48.dp))
            Slider(
                value = pan,
                onValueChange = {
                    // Snap near the center so it is easy to land exactly there.
                    pan = if (abs(it) < 0.04f) 0f else it
                    actions.onPreviewPan(track.id, pan)
                },
                onValueChangeFinished = { actions.onSetPan(track.id, pan) },
                valueRange = -1f..1f,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { pan = 0f; actions.onSetPan(track.id, 0f) }) {
                MonoValue(formatPan(pan))
            }
        }
    }
}

@Composable
private fun MasterSection(state: RecorderUiState, live: Boolean, actions: MixerActions) {
    val mixer = state.project?.mixer ?: return
    val status = state.status
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.mixer_master), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("L", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(14.dp))
            LevelMeter(if (live) status.masterPeakLeft else 0f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("R", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(14.dp))
            LevelMeter(if (live) status.masterPeakRight else 0f)
        }
        var volume by remember(mixer.masterVolumeDb) { mutableFloatStateOf(mixer.masterVolumeDb.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mixer_volume), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(48.dp))
            Slider(
                value = volume,
                onValueChange = { volume = it; actions.onPreviewMaster(it.toDouble()) },
                onValueChangeFinished = { actions.onSetMaster(volume.toDouble()) },
                valueRange = Mixing.MIN_MASTER_DB.toFloat()..Mixing.MAX_MASTER_DB.toFloat(),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { volume = 0f; actions.onSetMaster(0.0) }) {
                MonoValue(formatDb(volume, Mixing.MIN_MASTER_DB))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mixer_limiter), style = MaterialTheme.typography.bodyMedium)
                val reduction = if (live && mixer.limiter && status.limiterGain < 0.999f) {
                    -20f * log10(status.limiterGain.coerceAtLeast(1e-4f))
                } else 0f
                Text(
                    if (!mixer.limiter) stringResource(R.string.mixer_limiter_off)
                    else if (reduction >= 0.1f) stringResource(R.string.mixer_limiting,
                        String.format(Locale.ROOT, "%.1f", reduction))
                    else stringResource(R.string.mixer_limiter_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reduction >= 0.1f) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = mixer.limiter, onCheckedChange = actions.onLimiter)
        }
    }
}

@Composable
private fun MonoValue(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
}

private fun formatDb(db: Float, min: Double): String =
    if (db <= min) "−∞ dB" else String.format(Locale.ROOT, "%+.1f dB", db)

/** "C" at the center, otherwise "L 35" / "R 80" (percent toward that side). */
private fun formatPan(pan: Float): String {
    val percent = (abs(pan) * 100).roundToInt()
    return when {
        percent == 0 -> "  C  "
        pan < 0 -> String.format(Locale.ROOT, "L %3d", percent)
        else -> String.format(Locale.ROOT, "R %3d", percent)
    }
}
