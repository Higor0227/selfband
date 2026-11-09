// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import io.github.higor0227.selfband.core.audio.calibration.CalibrationFailure
import io.github.higor0227.selfband.core.audio.calibration.CalibrationOutcome
import io.github.higor0227.selfband.core.audio.calibration.CalibrationTask
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

/** Method choice, running measurement and result of a calibration or leak test. */
@Composable
internal fun CalibrationDialog(
    ui: CalibrationUi,
    onStart: (CalibrationTask) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    when (ui) {
        CalibrationUi.Idle -> Unit
        CalibrationUi.Choosing -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.calibration_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.calibration_intro), style = MaterialTheme.typography.bodySmall)
                    CalibrationTask.entries.forEach { task ->
                        Card(Modifier.fillMaxWidth().clickable { onStart(task) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(task.titleRes()), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(task.descriptionRes()), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) } },
        )
        is CalibrationUi.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(ui.task.titleRes())) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(ui.task.runningRes()), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) } },
        )
        is CalibrationUi.Finished -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(ui.task.titleRes())) },
            text = { ResultText(ui.outcome) },
            confirmButton = {
                val outcome = ui.outcome
                if (outcome is CalibrationOutcome.Calibrated && !outcome.saved) {
                    TextButton(onClick = onSave) { Text(stringResource(R.string.save_to_profile)) }
                } else if (outcome is CalibrationOutcome.Failed) {
                    TextButton(onClick = { onStart(ui.task) }) { Text(stringResource(R.string.try_again)) }
                } else {
                    TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) }
                }
            },
            dismissButton = {
                val outcome = ui.outcome
                if (outcome is CalibrationOutcome.Calibrated && !outcome.saved || outcome is CalibrationOutcome.Failed) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.discard)) }
                }
            },
        )
    }
}

@Composable
private fun ResultText(outcome: CalibrationOutcome) {
    val f = { v: Double -> String.format(Locale.ROOT, "%.1f", v) }
    when (outcome) {
        is CalibrationOutcome.Calibrated -> {
            val c = outcome.calibration
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.result_round_trip, f(c.roundTripMs)), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.result_details,
                        String.format(Locale.ROOT, "%+.2f", c.residualMs),
                        String.format(Locale.ROOT, "%.2f", c.spreadMs),
                        c.accepted, c.attempts,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (c.spreadMs > 1.0) {
                    Text(stringResource(R.string.result_spread_high), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(if (outcome.saved) R.string.result_saved else R.string.result_save_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        is CalibrationOutcome.Leak -> {
            val r = outcome.result
            if (r.detected) {
                Text(
                    stringResource(
                        R.string.leak_found,
                        String.format(Locale.ROOT, "%.0f", r.levelDb ?: 0.0),
                        f(r.delayMs ?: 0.0),
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(stringResource(R.string.leak_not_found))
            }
        }
        is CalibrationOutcome.Failed -> Text(
            stringResource(
                when (outcome.reason) {
                    CalibrationFailure.NO_SIGNAL -> R.string.calibration_no_signal
                    CalibrationFailure.INCONSISTENT -> R.string.calibration_inconsistent
                    CalibrationFailure.INTERRUPTED -> R.string.calibration_interrupted
                },
            ),
        )
    }
}

private fun CalibrationTask.titleRes() = when (this) {
    CalibrationTask.ACOUSTIC -> R.string.method_acoustic
    CalibrationTask.LOOPBACK -> R.string.method_loopback
    CalibrationTask.TAP -> R.string.method_tap
    CalibrationTask.LEAK -> R.string.leak_test
}

private fun CalibrationTask.descriptionRes() = when (this) {
    CalibrationTask.ACOUSTIC -> R.string.method_acoustic_desc
    CalibrationTask.LOOPBACK -> R.string.method_loopback_desc
    CalibrationTask.TAP -> R.string.method_tap_desc
    CalibrationTask.LEAK -> R.string.leak_test_desc
}

private fun CalibrationTask.runningRes() = when (this) {
    CalibrationTask.ACOUSTIC -> R.string.running_acoustic
    CalibrationTask.LOOPBACK -> R.string.running_loopback
    CalibrationTask.TAP -> R.string.running_tap
    CalibrationTask.LEAK -> R.string.running_leak
}
