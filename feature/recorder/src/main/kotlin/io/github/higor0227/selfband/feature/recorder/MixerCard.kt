// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.higor0227.selfband.core.model.Levels
import io.github.higor0227.selfband.core.model.Mixing
import io.github.higor0227.selfband.core.model.ReverbPreset
import io.github.higor0227.selfband.core.model.ReverbSettings
import io.github.higor0227.selfband.core.model.Track
import io.github.higor0227.selfband.core.model.TrackEq
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
    val onTogglePhase: (String) -> Unit,
    val onPreviewEq: (String, TrackEq) -> Unit,
    val onSetEq: (String, TrackEq) -> Unit,
    val onPreviewSend: (String, Double) -> Unit,
    val onSetSend: (String, Double) -> Unit,
    val onPreviewReverb: (ReverbSettings) -> Unit,
    val onSetReverb: (ReverbSettings) -> Unit,
)

/**
 * Mixer (SPEC 2.5): per track volume, pan, mute, solo, phase, EQ and reverb send with a meter,
 * then the send reverb, the master volume and the clipping protection. Everything is heard at
 * once, also during playback.
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
            val engineLive = LocalLiveStatus.current
            project.tracks.forEachIndexed { index, track ->
                MixerTrackRow(
                    index = index,
                    track = track,
                    sampleRate = project.sampleRate,
                    silencedBySolo = anySolo && !track.solo && !track.muted,
                    peak = { if (live) state.trackPeak(engineLive.value, track.id) else 0f },
                    actions = actions,
                )
                HorizontalDivider()
            }
            ReverbSection(project.mixer.reverb, actions)
            HorizontalDivider()
            MasterSection(state, live, actions)
        }
    }
}

@Composable
private fun MixerTrackRow(
    index: Int,
    track: Track,
    sampleRate: Int,
    silencedBySolo: Boolean,
    peak: () -> Float,
    actions: MixerActions,
) {
    var eqOpen by remember { mutableStateOf(false) }
    val phaseLabel = stringResource(R.string.mixer_phase)
    if (eqOpen) {
        EqDialog(
            trackNumber = index + 1,
            initial = track.eq,
            sampleRate = sampleRate,
            onPreview = { actions.onPreviewEq(track.id, it) },
            onSet = { actions.onSetEq(track.id, it) },
            onClose = { eqOpen = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.track_n, index + 1), style = MaterialTheme.typography.labelLarge)
                if (silencedBySolo) {
                    Text(stringResource(R.string.mixer_silenced_by_solo), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Highlighted when the EQ changes the sound.
            FilterChip(
                selected = !track.eq.isNeutral,
                onClick = { eqOpen = true },
                label = { Text(stringResource(R.string.mixer_eq_short)) },
            )
            FilterChip(
                selected = track.phaseInverted,
                onClick = { actions.onTogglePhase(track.id) },
                label = { Text("\u00D8") },
                modifier = Modifier.semantics { contentDescription = phaseLabel },
            )
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
        // Reverb send (post-fader): tap the value to turn it off.
        var send by remember(track.reverbSendDb) { mutableFloatStateOf(track.reverbSendDb.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mixer_send), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(48.dp))
            Slider(
                value = send,
                onValueChange = { send = it; actions.onPreviewSend(track.id, it.toDouble()) },
                onValueChangeFinished = { actions.onSetSend(track.id, send.toDouble()) },
                valueRange = Levels.MIN_DB.toFloat()..Mixing.MAX_SEND_DB.toFloat(),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { send = Levels.MIN_DB.toFloat(); actions.onSetSend(track.id, Levels.MIN_DB) }) {
                MonoValue(formatDb(send, Levels.MIN_DB))
            }
        }
    }
}

/** The single send reverb: presets and the four room controls plus the return level. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReverbSection(reverb: ReverbSettings, actions: MixerActions) {
    var draft by remember(reverb) { mutableStateOf(reverb) }
    // Any manual change leaves the preset (custom).
    fun drag(next: ReverbSettings) { draft = next.copy(preset = null); actions.onPreviewReverb(draft) }
    fun commit() { actions.onSetReverb(draft) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.mixer_reverb), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReverbPreset.entries.forEach { preset ->
                FilterChip(
                    selected = draft.preset == preset,
                    onClick = {
                        // Presets keep the return level the user set.
                        val next = ReverbSettings.of(preset).copy(returnDb = draft.returnDb)
                        draft = next
                        actions.onSetReverb(next)
                    },
                    label = { Text(stringResource(preset.labelRes())) },
                )
            }
        }
        ReverbSlider(stringResource(R.string.reverb_size), draft.size.toFloat(), 0f..1f,
            String.format(Locale.ROOT, "%3d%%", (draft.size * 100).roundToInt()),
            { drag(draft.copy(size = it.toDouble())) }, ::commit)
        ReverbSlider(stringResource(R.string.reverb_decay), draft.decaySeconds.toFloat(),
            ReverbSettings.MIN_DECAY_S.toFloat()..ReverbSettings.MAX_DECAY_S.toFloat(),
            String.format(Locale.ROOT, "%.1f s", draft.decaySeconds),
            { drag(draft.copy(decaySeconds = it.toDouble())) }, ::commit)
        ReverbSlider(stringResource(R.string.reverb_pre_delay), draft.preDelayMs.toFloat(),
            0f..ReverbSettings.MAX_PRE_DELAY_MS.toFloat(),
            String.format(Locale.ROOT, "%3.0f ms", draft.preDelayMs),
            { drag(draft.copy(preDelayMs = it.toDouble())) }, ::commit)
        ReverbSlider(stringResource(R.string.reverb_damping), draft.damping.toFloat(), 0f..1f,
            String.format(Locale.ROOT, "%3d%%", (draft.damping * 100).roundToInt()),
            { drag(draft.copy(damping = it.toDouble())) }, ::commit)
        ReverbSlider(stringResource(R.string.reverb_return), draft.returnDb.toFloat(),
            ReverbSettings.MIN_RETURN_DB.toFloat()..ReverbSettings.MAX_RETURN_DB.toFloat(),
            formatDb(draft.returnDb.toFloat(), ReverbSettings.MIN_RETURN_DB),
            // The return level is not part of a preset's character: keep the preset.
            { draft = draft.copy(returnDb = it.toDouble()); actions.onPreviewReverb(draft) }, ::commit)
        Text(stringResource(R.string.reverb_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReverbSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    text: String,
    onDrag: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(88.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onDrag,
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(64.dp))
    }
}

private fun ReverbPreset.labelRes(): Int = when (this) {
    ReverbPreset.SMALL_ROOM -> R.string.reverb_small_room
    ReverbPreset.ROOM -> R.string.reverb_room
    ReverbPreset.HALL -> R.string.reverb_hall
    ReverbPreset.PLATE -> R.string.reverb_plate
}

@Composable
private fun MasterSection(state: RecorderUiState, live: Boolean, actions: MixerActions) {
    val mixer = state.project?.mixer ?: return
    val engineLive = LocalLiveStatus.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.mixer_master), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("L", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(14.dp))
            LevelMeter({ if (live) engineLive.value.masterPeakLeft else 0f })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("R", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(14.dp))
            LevelMeter({ if (live) engineLive.value.masterPeakRight else 0f })
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
                LimiterStatus(mixer.limiter, live)
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

/** Limiter state text: the only part of the master section that follows the live gain reduction. */
@Composable
private fun LimiterStatus(on: Boolean, live: Boolean) {
    val gain = LocalLiveStatus.current.value.limiterGain
    val reduction = if (live && on && gain < 0.999f) -20f * log10(gain.coerceAtLeast(1e-4f)) else 0f
    Text(
        if (!on) stringResource(R.string.mixer_limiter_off)
        else if (reduction >= 0.1f) stringResource(R.string.mixer_limiting, String.format(Locale.ROOT, "%.1f", reduction))
        else stringResource(R.string.mixer_limiter_hint),
        style = MaterialTheme.typography.bodySmall,
        color = if (reduction >= 0.1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
