// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.settings.onboarding

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.higor0227.selfband.core.audio.AudioDeviceMonitor
import io.github.higor0227.selfband.core.audio.AudioEngine
import io.github.higor0227.selfband.core.audio.AudioRoute
import io.github.higor0227.selfband.core.audio.AudioRouting
import io.github.higor0227.selfband.core.audio.EngineConfig
import io.github.higor0227.selfband.core.audio.EngineOpenResult
import io.github.higor0227.selfband.core.audio.RecordChannelMode
import io.github.higor0227.selfband.core.audio.TransportState
import io.github.higor0227.selfband.core.audio.calibration.CalibrationFailure
import io.github.higor0227.selfband.core.audio.calibration.CalibrationOutcome
import io.github.higor0227.selfband.core.audio.calibration.CalibrationTask
import io.github.higor0227.selfband.core.audio.calibration.RouteCalibrator
import io.github.higor0227.selfband.core.model.AspectRatio
import io.github.higor0227.selfband.core.model.AudioDevice
import io.github.higor0227.selfband.core.model.DeviceProfile
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.Levels
import io.github.higor0227.selfband.core.model.MetronomeSettings
import io.github.higor0227.selfband.core.model.RecordingSetup
import io.github.higor0227.selfband.core.project.AppSettingsRepository
import io.github.higor0227.selfband.core.project.DeviceProfileRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

/** First-run wizard steps (SPEC 2.8), in order. */
enum class OnboardingStep { PERMISSIONS, SETUP, CALIBRATION, FORMAT }

sealed interface WizardCalibration {
    data object Idle : WizardCalibration
    data class Running(val task: CalibrationTask, val progress: Float) : WizardCalibration
    data class Finished(val task: CalibrationTask, val outcome: CalibrationOutcome) : WizardCalibration
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.PERMISSIONS,
    val setup: RecordingSetup = RecordingSetup.PHONE_MIC,
    val devices: List<AudioDevice> = emptyList(),
    val micGranted: Boolean = false,
    /** Route of the open engine during the calibration step; null while closed or opening. */
    val route: AudioRoute? = null,
    val profile: DeviceProfile? = null,
    val engineError: String? = null,
    val calibration: WizardCalibration = WizardCalibration.Idle,
    val aspectRatio: AspectRatio = AspectRatio.PORTRAIT_9_16,
) {
    val setupConnected: Boolean get() = setup.isConnected(devices)

    /** Methods that fit [setup], best first (SPEC 2.4); the leak test is offered apart. */
    val methods: List<CalibrationTask>
        get() = when (setup) {
            RecordingSetup.PHONE_MIC, RecordingSetup.WIRED, RecordingSetup.BLUETOOTH ->
                listOf(CalibrationTask.ACOUSTIC, CalibrationTask.TAP)
            RecordingSetup.USB_INTERFACE -> listOf(CalibrationTask.LOOPBACK, CalibrationTask.TAP)
        }

    /** Only interfaces can send the playback back into their input (SPEC 2.4). */
    val offersLeakTest: Boolean get() = setup == RecordingSetup.USB_INTERFACE
}

@OptIn(FlowPreview::class)
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedState: SavedStateHandle,
    private val settings: AppSettingsRepository,
    private val engine: AudioEngine,
    private val deviceMonitor: AudioDeviceMonitor,
    private val profiles: DeviceProfileRepository,
    private val calibrator: RouteCalibrator,
) : ViewModel() {

    private val local = MutableStateFlow(
        OnboardingUiState(step = savedState.get<String>(KEY_STEP)?.let(OnboardingStep::valueOf) ?: OnboardingStep.PERMISSIONS),
    )

    val uiState: StateFlow<OnboardingUiState> =
        combine(local, deviceMonitor.devices, profiles.profiles) { l, devices, all ->
            l.copy(devices = devices, profile = l.route?.let { r -> all.firstOrNull { it.key == r.key } })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    /** Serializes engine open/close and the measurement. */
    private val ops = Mutex()
    private var screenActive = false
    private var engineOpen = false
    private var calibrationJob: Job? = null

    init {
        viewModelScope.launch {
            val stored = settings.get()
            val setup = stored.recordingSetup ?: RecordingSetup.detect(deviceMonitor.devices.value)
            local.update { it.copy(setup = setup, aspectRatio = stored.aspectRatio) }
        }
        // Device plugged/unplugged during the calibration step: reopen on the new route.
        viewModelScope.launch {
            deviceMonitor.devices.drop(1).debounce(DEVICE_DEBOUNCE_MS).collect {
                if (engineOpen) ops.withLock { closeEngine(); syncEngine() }
            }
        }
        viewModelScope.launch {
            engine.status.map { it.outputDisconnected || it.inputDisconnected }
                .distinctUntilChanged().filter { it }
                .collect { if (engineOpen) ops.withLock { closeEngine(); syncEngine() } }
        }
    }

    fun onStart() {
        screenActive = true
        viewModelScope.launch { ops.withLock { syncEngine() } }
    }

    fun onStop() {
        screenActive = false
        cancelCalibration()
        viewModelScope.launch { ops.withLock { syncEngine() } }
    }

    override fun onCleared() {
        if (engineOpen) MainScope().launch { engine.stop(); engine.close() }
    }

    fun setMicPermission(granted: Boolean) {
        if (local.value.micGranted == granted) return
        local.update { it.copy(micGranted = granted) }
        viewModelScope.launch { ops.withLock { syncEngine() } }
    }

    fun setSetup(setup: RecordingSetup) = local.update { it.copy(setup = setup) }

    fun setAspectRatio(ratio: AspectRatio) {
        local.update { it.copy(aspectRatio = ratio) }
        viewModelScope.launch { settings.update { it.copy(aspectRatio = ratio) } }
    }

    fun next() {
        val step = local.value.step
        if (step == OnboardingStep.SETUP) {
            val setup = local.value.setup
            viewModelScope.launch { settings.update { it.copy(recordingSetup = setup) } }
        }
        OnboardingStep.entries.getOrNull(step.ordinal + 1)?.let(::goTo)
    }

    fun back() {
        OnboardingStep.entries.getOrNull(local.value.step.ordinal - 1)?.let(::goTo)
    }

    private fun goTo(step: OnboardingStep) {
        cancelCalibration()
        savedState[KEY_STEP] = step.name
        local.update { it.copy(step = step, calibration = WizardCalibration.Idle) }
        viewModelScope.launch { ops.withLock { syncEngine() } }
    }

    /** Finished or skipped: the wizard is not shown again (it stays in Settings). */
    fun finish(onDone: () -> Unit) {
        cancelCalibration()
        val state = local.value
        viewModelScope.launch {
            ops.withLock { closeEngine() }
            settings.update { it.copy(onboardingDone = true, recordingSetup = state.setup, aspectRatio = state.aspectRatio) }
            Log.i(LOG_TAG, "Onboarding finished: setup=${state.setup} format=${state.aspectRatio}")
            onDone()
        }
    }

    // --- calibration (SPEC 2.4) ----------------------------------------------------------

    fun startCalibration(task: CalibrationTask) {
        if (local.value.calibration is WizardCalibration.Running) return
        calibrationJob = viewModelScope.launch {
            ops.withLock {
                val status = engine.status.value
                val route = local.value.route
                if (!engineOpen || !status.isOpen || status.transport != TransportState.IDLE || route == null) {
                    local.update { it.copy(calibration = WizardCalibration.Finished(task, CalibrationOutcome.Failed(CalibrationFailure.INTERRUPTED))) }
                    return@withLock
                }
                local.update { it.copy(calibration = WizardCalibration.Running(task, 0f)) }
                val profile = profiles.get(route.key)
                val measured = try {
                    calibrator.measure(task, profile, File(context.cacheDir, "calibration.wav")) { progress ->
                        local.update { s ->
                            val running = s.calibration as? WizardCalibration.Running ?: return@update s
                            s.copy(calibration = running.copy(progress = progress))
                        }
                    }
                } finally {
                    withContext(NonCancellable) { if (engine.status.value.isOpen) silence(profiles.get(route.key)) }
                }
                // A guided first calibration is saved right away; it can be repeated or redone in the recorder.
                val outcome = when (measured) {
                    is CalibrationOutcome.Calibrated -> {
                        save(route) { it.copy(calibration = measured.calibration) }
                        Log.i(LOG_TAG, "Onboarding calibration saved for ${route.key}: ${measured.calibration}")
                        measured.copy(saved = true)
                    }
                    is CalibrationOutcome.Leak -> {
                        save(route) { it.copy(leak = measured.result) }
                        measured
                    }
                    is CalibrationOutcome.Failed -> measured
                }
                local.update { it.copy(calibration = WizardCalibration.Finished(task, outcome)) }
            }
        }
    }

    fun cancelCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        if (local.value.calibration is WizardCalibration.Running) {
            local.update { it.copy(calibration = WizardCalibration.Idle) }
        }
    }

    fun closeResult() = local.update { it.copy(calibration = WizardCalibration.Idle) }

    // --- engine (call with `ops` held) -----------------------------------------------------

    /** The engine runs only on the calibration step, while the screen is visible and the mic is allowed. */
    private suspend fun syncEngine() {
        val want = screenActive && local.value.step == OnboardingStep.CALIBRATION && local.value.micGranted
        if (want && !engineOpen) openEngine() else if (!want && engineOpen) closeEngine()
    }

    private suspend fun openEngine() {
        val input = AudioRouting.preferredInput(deviceMonitor.devices.value)
        val config = EngineConfig(
            sampleRate = deviceMonitor.systemInfo().nativeSampleRate ?: DEFAULT_SAMPLE_RATE,
            inputChannelCount = AudioRouting.inputChannelCount(input),
            inputPreset = AudioRouting.inputPreset(deviceMonitor.systemInfo()),
            inputDeviceId = input?.id ?: 0,
        )
        when (val result = engine.open(config)) {
            is EngineOpenResult.Failed -> local.update { it.copy(engineError = result.reason, route = null) }
            EngineOpenResult.Opened -> {
                engineOpen = true
                val status = withTimeoutOrNull(STATUS_WAIT_MS) { engine.status.first { it.isOpen } } ?: engine.status.value
                val route = AudioRoute.of(deviceMonitor.devices.value, status, input)
                Log.i(LOG_TAG, "Onboarding: engine open on ${route.key}")
                engine.setTracks(emptyList())
                silence(profiles.get(route.key))
                local.update { it.copy(engineError = null, route = route) }
            }
        }
    }

    private suspend fun closeEngine() {
        if (!engineOpen) return
        engine.stop()
        engine.close()
        engineOpen = false
        local.update { it.copy(route = null) }
    }

    /** No clicks and no software monitoring between measurements. */
    private suspend fun silence(profile: DeviceProfile?) {
        engine.setMetronome(MetronomeSettings(enabled = false, countInBars = 0), 0)
        engine.setInputProcessing(Levels.dbToLinear(profile?.inputGainDb ?: 0.0).toFloat(), 0f, RecordChannelMode.FIRST)
    }

    private suspend fun save(route: AudioRoute, transform: (DeviceProfile) -> DeviceProfile) {
        val sampleRate = engine.status.value.sampleRate
        profiles.upsert(route.key, route::newProfile) { current ->
            transform(current).copy(
                outputLabel = DeviceProfile.deviceLabel(route.output),
                inputLabel = DeviceProfile.deviceLabel(route.input),
                lastSampleRate = if (sampleRate > 0) sampleRate else current.lastSampleRate,
            )
        }
    }

    private companion object {
        const val KEY_STEP = "step"
        const val DEFAULT_SAMPLE_RATE = 48_000
        const val STATUS_WAIT_MS = 1_000L
        const val DEVICE_DEBOUNCE_MS = 500L
    }
}
