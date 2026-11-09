// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.settings.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.higor0227.selfband.core.audio.calibration.CalibrationFailure
import io.github.higor0227.selfband.core.audio.calibration.CalibrationOutcome
import io.github.higor0227.selfband.core.audio.calibration.CalibrationTask
import io.github.higor0227.selfband.core.model.AspectRatio
import io.github.higor0227.selfband.core.model.RecordingSetup
import io.github.higor0227.selfband.core.ui.component.audioDeviceLabel
import io.github.higor0227.selfband.feature.settings.R
import java.util.Locale

/** The permissions the wizard asks for (SPEC 3.7); notifications only exist as a permission on 13+. */
private val PERMISSIONS = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

@Composable
fun OnboardingRoute(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    fun check() = PERMISSIONS.associateWith {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(check()) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = check()
        asked = true
    }
    // Permissions may also be changed in the system settings while the app is in the background.
    LifecycleResumeEffect(Unit) {
        granted = check()
        onPauseOrDispose { }
    }
    val micGranted = granted[Manifest.permission.RECORD_AUDIO] == true
    LaunchedEffect(micGranted) { viewModel.setMicPermission(micGranted) }
    LifecycleStartEffect(Unit) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = state.step != OnboardingStep.PERMISSIONS) { viewModel.back() }
    OnboardingScreen(
        state = state,
        granted = granted,
        asked = asked,
        onRequestPermissions = { launcher.launch(granted.filterValues { !it }.keys.toTypedArray()) },
        onSetup = viewModel::setSetup,
        onStartCalibration = viewModel::startCalibration,
        onCancelCalibration = viewModel::cancelCalibration,
        onCloseResult = viewModel::closeResult,
        onAspectRatio = viewModel::setAspectRatio,
        onBack = viewModel::back,
        onNext = viewModel::next,
        onFinish = { viewModel.finish(onDone) },
    )
}

/** First-run wizard (SPEC 2.8): permissions, "How do you record?", guided calibration, default format. */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    granted: Map<String, Boolean>,
    asked: Boolean,
    onRequestPermissions: () -> Unit,
    onSetup: (RecordingSetup) -> Unit,
    onStartCalibration: (CalibrationTask) -> Unit,
    onCancelCalibration: () -> Unit,
    onCloseResult: () -> Unit,
    onAspectRatio: (AspectRatio) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    val steps = OnboardingStep.entries
    val last = state.step == steps.last()
    val running = state.calibration is WizardCalibration.Running
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.onboarding_step, state.step.ordinal + 1, steps.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (!last) TextButton(onClick = onFinish, enabled = !running) { Text(stringResource(R.string.onboarding_skip)) }
            }
            LinearProgressIndicator(
                progress = { (state.step.ordinal + 1f) / steps.size },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.step) {
                    OnboardingStep.PERMISSIONS -> PermissionsStep(granted, asked, onRequestPermissions)
                    OnboardingStep.SETUP -> SetupStep(state, onSetup)
                    OnboardingStep.CALIBRATION -> CalibrationStep(state, onStartCalibration, onCancelCalibration, onCloseResult)
                    OnboardingStep.FORMAT -> FormatStep(state.aspectRatio, onAspectRatio)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.step != steps.first()) {
                    OutlinedButton(onClick = onBack, enabled = !running) { Text(stringResource(R.string.onboarding_back)) }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = if (last) onFinish else onNext, enabled = !running) {
                    Text(stringResource(if (last) R.string.onboarding_finish else R.string.onboarding_next))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PermissionsStep(granted: Map<String, Boolean>, asked: Boolean, onRequest: () -> Unit) {
    Title(stringResource(R.string.onboarding_welcome))
    Body(stringResource(R.string.onboarding_welcome_text))
    granted.forEach { (permission, ok) ->
        val (title, text) = when (permission) {
            Manifest.permission.RECORD_AUDIO -> R.string.onboarding_perm_mic to R.string.onboarding_perm_mic_text
            Manifest.permission.CAMERA -> R.string.onboarding_perm_camera to R.string.onboarding_perm_camera_text
            else -> R.string.onboarding_perm_notifications to R.string.onboarding_perm_notifications_text
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Outlined.Info,
                contentDescription = stringResource(if (ok) R.string.onboarding_perm_granted else R.string.onboarding_perm_missing),
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (granted.values.any { !it }) {
        FilledTonalButton(onClick = onRequest) { Text(stringResource(R.string.onboarding_perm_allow)) }
        if (asked) Hint(stringResource(R.string.onboarding_perm_later))
    } else {
        Hint(stringResource(R.string.onboarding_perm_all_granted))
    }
}

@Composable
private fun ColumnScope.SetupStep(state: OnboardingUiState, onSetup: (RecordingSetup) -> Unit) {
    Title(stringResource(R.string.onboarding_setup_title))
    RecordingSetup.entries.forEach { setup ->
        val selected = state.setup == setup
        Card(
            Modifier.fillMaxWidth().selectable(selected = selected, onClick = { onSetup(setup) }, role = Role.RadioButton),
            colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            else CardDefaults.cardColors(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(setup.title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(setup.description), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    val connected = state.devices.filter { !it.isInput }.map { audioDeviceLabel(it) } +
        state.devices.filter { it.isInput }.map { audioDeviceLabel(it) }
    if (connected.isNotEmpty()) Hint(stringResource(R.string.onboarding_connected, connected.distinct().joinToString(", ")))
    if (!state.setupConnected) Warning(stringResource(R.string.onboarding_setup_not_connected))
}

@Composable
private fun ColumnScope.CalibrationStep(
    state: OnboardingUiState,
    onStart: (CalibrationTask) -> Unit,
    onCancel: () -> Unit,
    onCloseResult: () -> Unit,
) {
    Title(stringResource(R.string.onboarding_calibration_title))
    Body(stringResource(R.string.onboarding_calibration_text))
    if (!state.micGranted) {
        Warning(stringResource(R.string.onboarding_calibration_no_mic))
        return
    }
    state.engineError?.let { Warning(stringResource(R.string.onboarding_engine_error, it)) }
    val route = state.route
    if (route == null) {
        if (state.engineError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.onboarding_output, audioDeviceLabel(route.output)), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.onboarding_input, route.input?.let { audioDeviceLabel(it) } ?: "?"),
                style = MaterialTheme.typography.bodyMedium,
            )
            state.profile?.calibration?.let {
                Text(
                    stringResource(R.string.onboarding_already_calibrated, String.format(Locale.ROOT, "%.1f", it.roundTripMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (!state.setupConnected) Warning(stringResource(R.string.onboarding_setup_not_connected))
    if (state.setup == RecordingSetup.BLUETOOTH || route.isBluetooth) Warning(stringResource(R.string.onboarding_bluetooth_warning))

    when (val c = state.calibration) {
        is WizardCalibration.Running -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(c.task.title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(c.task.running), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { c.progress }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            }
        }
        is WizardCalibration.Finished -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(c.task.title), style = MaterialTheme.typography.titleSmall)
                Result(c.outcome)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onStart(c.task) }) { Text(stringResource(R.string.onboarding_repeat)) }
                    TextButton(onClick = onCloseResult) { Text(stringResource(R.string.onboarding_other_method)) }
                }
            }
        }
        WizardCalibration.Idle -> {
            state.methods.forEachIndexed { i, task ->
                MethodCard(task, instruction(task, state.setup), recommended = i == 0) { onStart(task) }
            }
            if (state.offersLeakTest) {
                MethodCard(CalibrationTask.LEAK, R.string.onboarding_leak_text, recommended = false) { onStart(CalibrationTask.LEAK) }
            }
        }
    }
    Hint(stringResource(R.string.onboarding_calibration_later))
}

@Composable
private fun MethodCard(task: CalibrationTask, instruction: Int, recommended: Boolean, onStart: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(task.title), style = MaterialTheme.typography.titleSmall)
                if (recommended) {
                    Text(
                        stringResource(R.string.onboarding_recommended),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(stringResource(instruction), style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = onStart) { Text(stringResource(R.string.onboarding_start)) }
        }
    }
}

@Composable
private fun Result(outcome: CalibrationOutcome) {
    val f = { v: Double -> String.format(Locale.ROOT, "%.1f", v) }
    when (outcome) {
        is CalibrationOutcome.Calibrated -> {
            val c = outcome.calibration
            Text(stringResource(R.string.onboarding_result, f(c.roundTripMs)), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.onboarding_result_details, String.format(Locale.ROOT, "%.2f", c.spreadMs), c.accepted, c.attempts),
                style = MaterialTheme.typography.bodySmall,
            )
            if (c.spreadMs > 1.0) Warning(stringResource(R.string.onboarding_result_spread_high))
            Text(stringResource(R.string.onboarding_result_saved), style = MaterialTheme.typography.bodySmall)
        }
        is CalibrationOutcome.Leak -> {
            val r = outcome.result
            if (r.detected) {
                Warning(stringResource(R.string.onboarding_leak_found, String.format(Locale.ROOT, "%.0f", r.levelDb ?: 0.0)))
            } else {
                Text(stringResource(R.string.onboarding_leak_not_found))
            }
        }
        is CalibrationOutcome.Failed -> Warning(
            stringResource(
                when (outcome.reason) {
                    CalibrationFailure.NO_SIGNAL -> R.string.onboarding_failed_no_signal
                    CalibrationFailure.INCONSISTENT -> R.string.onboarding_failed_inconsistent
                    CalibrationFailure.INTERRUPTED -> R.string.onboarding_failed_interrupted
                },
            ),
        )
    }
}

@Composable
private fun ColumnScope.FormatStep(selected: AspectRatio, onSelect: (AspectRatio) -> Unit) {
    Title(stringResource(R.string.onboarding_format_title))
    Body(stringResource(R.string.onboarding_format_text))
    AspectRatio.entries.forEach { ratio ->
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = ratio == selected, onClick = { onSelect(ratio) }, role = Role.RadioButton)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = ratio == selected, onClick = null, modifier = Modifier.padding(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(ratio.title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(ratio.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Warning(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

private fun instruction(task: CalibrationTask, setup: RecordingSetup): Int = when (task) {
    CalibrationTask.ACOUSTIC ->
        if (setup == RecordingSetup.PHONE_MIC) R.string.onboarding_acoustic_phone else R.string.onboarding_acoustic_earbud
    CalibrationTask.LOOPBACK -> R.string.onboarding_loopback_text
    CalibrationTask.TAP -> R.string.onboarding_tap_text
    CalibrationTask.LEAK -> R.string.onboarding_leak_text
}

private val CalibrationTask.title: Int
    get() = when (this) {
        CalibrationTask.ACOUSTIC -> R.string.onboarding_method_acoustic
        CalibrationTask.LOOPBACK -> R.string.onboarding_method_loopback
        CalibrationTask.TAP -> R.string.onboarding_method_tap
        CalibrationTask.LEAK -> R.string.onboarding_method_leak
    }

private val CalibrationTask.running: Int
    get() = when (this) {
        CalibrationTask.ACOUSTIC -> R.string.onboarding_running_acoustic
        CalibrationTask.LOOPBACK -> R.string.onboarding_running_loopback
        CalibrationTask.TAP -> R.string.onboarding_running_tap
        CalibrationTask.LEAK -> R.string.onboarding_running_leak
    }

private val RecordingSetup.title: Int
    get() = when (this) {
        RecordingSetup.PHONE_MIC -> R.string.onboarding_setup_phone
        RecordingSetup.WIRED -> R.string.onboarding_setup_wired
        RecordingSetup.USB_INTERFACE -> R.string.onboarding_setup_usb
        RecordingSetup.BLUETOOTH -> R.string.onboarding_setup_bluetooth
    }

private val RecordingSetup.description: Int
    get() = when (this) {
        RecordingSetup.PHONE_MIC -> R.string.onboarding_setup_phone_text
        RecordingSetup.WIRED -> R.string.onboarding_setup_wired_text
        RecordingSetup.USB_INTERFACE -> R.string.onboarding_setup_usb_text
        RecordingSetup.BLUETOOTH -> R.string.onboarding_setup_bluetooth_text
    }

private val AspectRatio.title: Int
    get() = when (this) {
        AspectRatio.PORTRAIT_9_16 -> R.string.settings_aspect_9_16
        AspectRatio.LANDSCAPE_16_9 -> R.string.settings_aspect_16_9
        AspectRatio.SQUARE_1_1 -> R.string.settings_aspect_1_1
    }

private val AspectRatio.description: Int
    get() = when (this) {
        AspectRatio.PORTRAIT_9_16 -> R.string.onboarding_format_9_16
        AspectRatio.LANDSCAPE_16_9 -> R.string.onboarding_format_16_9
        AspectRatio.SQUARE_1_1 -> R.string.onboarding_format_1_1
    }
