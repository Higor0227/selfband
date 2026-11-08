// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import io.github.higor0227.selfband.core.audio.RecordChannelMode
import io.github.higor0227.selfband.core.model.CalibrationMethod
import io.github.higor0227.selfband.core.model.DeviceProfile
import io.github.higor0227.selfband.core.ui.component.LevelMeter
import java.util.Locale

/** Active route, its saved profile (calibration, leak test), input level/gain and monitoring. */
@Composable
internal fun DeviceCard(
    state: RecorderUiState,
    onChannelMode: (RecordChannelMode) -> Unit,
    onPreviewInputGain: (Double) -> Unit,
    onSetInputGain: (Double) -> Unit,
    onMonitoring: (Boolean) -> Unit,
    onPreviewMonitorGain: (Double) -> Unit,
    onSetMonitorGain: (Double) -> Unit,
    onCalibrate: () -> Unit,
) {
    val s = state.status
    val route = state.route
    val profile = state.profile
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.output_label) + ": " + DeviceProfile.deviceLabel(route?.output),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.input_label) + ": " +
                    (route?.input?.let { DeviceProfile.deviceLabel(it) } ?: stringResource(R.string.input_default)),
                style = MaterialTheme.typography.titleSmall,
            )
            if (route?.isBluetooth == true) {
                Text(stringResource(R.string.bluetooth_warning), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            // Calibration status of this route.
            val calibration = profile?.calibration
            Text(
                if (calibration == null) {
                    stringResource(R.string.not_calibrated)
                } else {
                    stringResource(
                        R.string.calibrated_value,
                        String.format(Locale.ROOT, "%.1f", calibration.roundTripMs),
                        String.format(Locale.ROOT, "%+.1f", calibration.residualMs),
                        stringResource(calibration.method.labelRes()),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
            )
            val leak = profile?.leak
            if (leak?.detected == true) {
                Text(
                    stringResource(R.string.leak_warning, String.format(Locale.ROOT, "%.0f", leak.levelDb ?: 0.0)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (leak != null) {
                Text(stringResource(R.string.leak_none), style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onCalibrate, enabled = s.isOpen && state.isIdle) {
                Text(stringResource(R.string.calibrate))
            }

            // Input level and digital gain.
            LevelMeter(s.peakLeft)
            if (s.inputChannels >= 2) {
                LevelMeter(s.peakRight)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChannelChip(R.string.channel_1, RecordChannelMode.FIRST, state, onChannelMode)
                    ChannelChip(R.string.channel_2, RecordChannelMode.SECOND, state, onChannelMode)
                    ChannelChip(R.string.channel_stereo, RecordChannelMode.STEREO, state, onChannelMode)
                }
            }
            val savedGain = profile?.inputGainDb ?: 0.0
            var gainDraft by remember(savedGain) { mutableFloatStateOf(savedGain.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.input_gain), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = gainDraft,
                    onValueChange = { gainDraft = it; onPreviewInputGain(it.toDouble()) },
                    onValueChangeFinished = { onSetInputGain(gainDraft.toDouble()) },
                    valueRange = DeviceProfile.MIN_INPUT_GAIN_DB.toFloat()..DeviceProfile.MAX_INPUT_GAIN_DB.toFloat(),
                    enabled = route != null && state.isIdle,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(String.format(Locale.ROOT, "%+.0f dB", gainDraft), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium)
            }

            // Software monitoring.
            val allowed = route?.monitoringAllowed == true
            val monitoring = profile?.monitoring == true && allowed
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.monitoring), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            !allowed -> stringResource(R.string.monitoring_not_on_speaker)
                            else -> stringResource(
                                R.string.monitoring_hint,
                                String.format(Locale.ROOT, "%.0f", (s.outputLatencyMs ?: 0.0) + (s.inputLatencyMs ?: 0.0)),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = monitoring, onCheckedChange = onMonitoring, enabled = allowed && route != null)
            }
            if (monitoring) {
                val savedMonitor = profile?.monitorGainDb ?: 0.0
                var monitorDraft by remember(savedMonitor) { mutableFloatStateOf(savedMonitor.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.monitor_volume), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = monitorDraft,
                        onValueChange = { monitorDraft = it; onPreviewMonitorGain(it.toDouble()) },
                        onValueChangeFinished = { onSetMonitorGain(monitorDraft.toDouble()) },
                        valueRange = -40f..12f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(String.format(Locale.ROOT, "%+.0f dB", monitorDraft), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ChannelChip(
    label: Int,
    mode: RecordChannelMode,
    state: RecorderUiState,
    onChannelMode: (RecordChannelMode) -> Unit,
) {
    FilterChip(
        selected = state.channelMode == mode,
        onClick = { onChannelMode(mode) },
        enabled = state.isIdle,
        label = { Text(stringResource(label)) },
    )
}

internal fun CalibrationMethod.labelRes() = when (this) {
    CalibrationMethod.ACOUSTIC -> R.string.method_acoustic
    CalibrationMethod.LOOPBACK -> R.string.method_loopback
    CalibrationMethod.TAP -> R.string.method_tap
}
