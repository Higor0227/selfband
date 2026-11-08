// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.higor0227.selfband.core.model.ClickSound
import io.github.higor0227.selfband.core.model.MetronomeSettings
import java.util.Locale
import kotlin.math.roundToInt

private val PRESET_SIGNATURES = listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8)

@Composable
internal fun MetronomeCard(
    settings: MetronomeSettings,
    onUpdate: ((MetronomeSettings) -> MetronomeSettings) -> Unit,
    onTap: (Long) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.metronome), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.metronome_summary, formatBpm(settings.bpm),
                            settings.beatsPerBar, settings.beatUnit, settings.countInBars,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = settings.enabled, onCheckedChange = { on -> onUpdate { it.copy(enabled = on) } })
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        stringResource(if (expanded) R.string.collapse else R.string.expand),
                    )
                }
            }
            if (!expanded) return@Column

            // Tempo: slider (live preview, saved on release), fine +/- buttons and tap tempo.
            var bpmDraft by remember(settings.bpm) { mutableFloatStateOf(settings.bpm.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onUpdate { it.copy(bpm = it.bpm.roundToInt() - 1.0) } }) { Text("−") }
                Text(
                    stringResource(R.string.bpm_value, formatBpm(bpmDraft.toDouble())),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = { onUpdate { it.copy(bpm = it.bpm.roundToInt() + 1.0) } }) { Text("+") }
                FilledTonalButton(onClick = { onTap(SystemClock.uptimeMillis()) }) {
                    Text(stringResource(R.string.tap_tempo))
                }
            }
            Slider(
                value = bpmDraft,
                onValueChange = { bpmDraft = it.roundToInt().toFloat() },
                onValueChangeFinished = { onUpdate { it.copy(bpm = bpmDraft.toDouble()) } },
                valueRange = MetronomeSettings.MIN_BPM.toFloat()..MetronomeSettings.MAX_BPM.toFloat(),
            )

            Text(stringResource(R.string.time_signature), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isPreset = PRESET_SIGNATURES.any { (b, u) -> b == settings.beatsPerBar && u == settings.beatUnit }
                PRESET_SIGNATURES.forEach { (beats, unit) ->
                    FilterChip(
                        selected = settings.beatsPerBar == beats && settings.beatUnit == unit,
                        onClick = { onUpdate { it.copy(beatsPerBar = beats, beatUnit = unit) } },
                        label = { Text("$beats/$unit") },
                    )
                }
                FilterChip(
                    selected = !isPreset,
                    onClick = { customDialog = true },
                    label = {
                        Text(
                            if (isPreset) stringResource(R.string.signature_custom)
                            else "${settings.beatsPerBar}/${settings.beatUnit}",
                        )
                    },
                )
            }

            Text(stringResource(R.string.count_in), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0..MetronomeSettings.MAX_COUNT_IN_BARS).forEach { bars ->
                    FilterChip(
                        selected = settings.countInBars == bars,
                        onClick = { onUpdate { it.copy(countInBars = bars) } },
                        label = {
                            Text(
                                if (bars == 0) stringResource(R.string.count_in_off)
                                else pluralStringResource(R.plurals.count_in_bars, bars, bars),
                            )
                        },
                    )
                }
            }

            Text(stringResource(R.string.click_sound), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClickSound.entries.forEach { sound ->
                    FilterChip(
                        selected = settings.sound == sound,
                        onClick = { onUpdate { it.copy(sound = sound) } },
                        label = { Text(stringResource(sound.labelRes())) },
                    )
                }
            }

            var volumeDraft by remember(settings.volume) { mutableFloatStateOf(settings.volume) }
            Text(
                stringResource(R.string.click_volume, (volumeDraft * 100).roundToInt()),
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = volumeDraft,
                onValueChange = { volumeDraft = it },
                onValueChangeFinished = { onUpdate { it.copy(volume = volumeDraft) } },
            )

            SwitchRow(R.string.accent_first_beat, settings.accentFirstBeat) { on ->
                onUpdate { it.copy(accentFirstBeat = on) }
            }
            SwitchRow(R.string.visual_flash, settings.visualFlash) { on -> onUpdate { it.copy(visualFlash = on) } }
            Text(stringResource(R.string.metronome_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (customDialog) {
        CustomSignatureDialog(
            settings = settings,
            onDismiss = { customDialog = false },
            onConfirm = { beats, unit ->
                customDialog = false
                onUpdate { it.copy(beatsPerBar = beats, beatUnit = unit) }
            },
        )
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CustomSignatureDialog(
    settings: MetronomeSettings,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var beats by remember { mutableStateOf(settings.beatsPerBar.toString()) }
    var unit by remember { mutableStateOf(settings.beatUnit.toString()) }
    val beatsValue = beats.toIntOrNull()?.takeIf { it in 1..16 }
    val unitValue = unit.toIntOrNull()?.takeIf { it in listOf(1, 2, 4, 8, 16) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.signature_custom)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = beats, onValueChange = { beats = it.take(2) }, singleLine = true,
                    isError = beatsValue == null, modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.beats_per_bar)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("/")
                OutlinedTextField(
                    value = unit, onValueChange = { unit = it.take(2) }, singleLine = true,
                    isError = unitValue == null, modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.beat_unit)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(beatsValue!!, unitValue!!) },
                enabled = beatsValue != null && unitValue != null,
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun ClickSound.labelRes() = when (this) {
    ClickSound.BEEP -> R.string.sound_beep
    ClickSound.WOOD -> R.string.sound_wood
    ClickSound.TICK -> R.string.sound_tick
}

internal fun formatBpm(bpm: Double): String =
    if (bpm % 1.0 == 0.0) bpm.toInt().toString() else String.format(Locale.getDefault(), "%.1f", bpm)
