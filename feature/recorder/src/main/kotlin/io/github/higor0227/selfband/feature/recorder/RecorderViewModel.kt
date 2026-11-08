// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.higor0227.selfband.core.audio.AudioDeviceMonitor
import io.github.higor0227.selfband.core.audio.AudioEngine
import io.github.higor0227.selfband.core.audio.AudioRouting
import io.github.higor0227.selfband.core.audio.EngineConfig
import io.github.higor0227.selfband.core.audio.EngineOpenResult
import io.github.higor0227.selfband.core.audio.EngineStatus
import io.github.higor0227.selfband.core.audio.PlaybackTrack
import io.github.higor0227.selfband.core.audio.RecordChannelMode
import io.github.higor0227.selfband.core.audio.RecordingResult
import io.github.higor0227.selfband.core.audio.TransportState
import io.github.higor0227.selfband.core.audio.alignment
import io.github.higor0227.selfband.core.audio.calibration.LatencyCalibrator
import io.github.higor0227.selfband.core.audio.calibration.MeasurementKind
import io.github.higor0227.selfband.core.audio.calibration.MeasurementResult
import io.github.higor0227.selfband.core.model.AlignmentInfo
import io.github.higor0227.selfband.core.model.AlignmentMath
import io.github.higor0227.selfband.core.model.AlignmentMethod
import io.github.higor0227.selfband.core.model.AudioDevice
import io.github.higor0227.selfband.core.model.AudioDeviceType
import io.github.higor0227.selfband.core.model.CalibrationMethod
import io.github.higor0227.selfband.core.model.DeviceProfile
import io.github.higor0227.selfband.core.model.InputChannel
import io.github.higor0227.selfband.core.model.LatencyCalibration
import io.github.higor0227.selfband.core.model.LeakTestResult
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.Levels
import io.github.higor0227.selfband.core.model.MetronomeSettings
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.model.Take
import io.github.higor0227.selfband.core.model.Track
import io.github.higor0227.selfband.core.project.DeviceProfileRepository
import io.github.higor0227.selfband.core.project.ProjectRepository
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
import kotlinx.coroutines.flow.filterNotNull
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
import java.util.UUID
import javax.inject.Inject

enum class RecorderNotice { DEVICE_CHANGED, TAKE_INTERRUPTED, TAKE_GLITCHED, RECORD_FAILED }

/** The devices the engine actually runs on, and the key of their saved profile. */
data class AudioRoute(val key: String, val output: AudioDevice?, val input: AudioDevice?) {
    /** Software monitoring is never allowed on a loudspeaker (feedback risk, SPEC 2.4). */
    val monitoringAllowed: Boolean
        get() = output != null && output.type !in LOUDSPEAKERS

    val isBluetooth: Boolean
        get() = output?.category == io.github.higor0227.selfband.core.model.AudioDeviceCategory.BLUETOOTH

    private companion object {
        val LOUDSPEAKERS = setOf(
            AudioDeviceType.BUILTIN_SPEAKER, AudioDeviceType.BUILTIN_EARPIECE, AudioDeviceType.BLE_SPEAKER,
            AudioDeviceType.HDMI, AudioDeviceType.TELEPHONY, AudioDeviceType.REMOTE_SUBMIX,
        )
    }
}

enum class CalibrationTask(val method: CalibrationMethod?) {
    ACOUSTIC(CalibrationMethod.ACOUSTIC),
    LOOPBACK(CalibrationMethod.LOOPBACK),
    TAP(CalibrationMethod.TAP),
    LEAK(null),
}

enum class CalibrationFailure { NO_SIGNAL, INCONSISTENT, INTERRUPTED }

sealed interface CalibrationOutcome {
    data class Calibrated(val calibration: LatencyCalibration, val saved: Boolean) : CalibrationOutcome
    data class Leak(val result: LeakTestResult) : CalibrationOutcome
    data class Failed(val reason: CalibrationFailure) : CalibrationOutcome
}

sealed interface CalibrationUi {
    data object Idle : CalibrationUi
    /** The user is choosing a method (dialog open, nothing running). */
    data object Choosing : CalibrationUi
    data class Running(val task: CalibrationTask, val progress: Float) : CalibrationUi
    data class Finished(val task: CalibrationTask, val outcome: CalibrationOutcome) : CalibrationUi
}

data class RecorderUiState(
    val project: Project? = null,
    val status: EngineStatus = EngineStatus(),
    val inputDevice: AudioDevice? = null,
    val channelMode: RecordChannelMode = RecordChannelMode.FIRST,
    val engineError: String? = null,
    val notice: RecorderNotice? = null,
    val freeBytes: Long = 0,
    /** Track ids in engine order (index into [EngineStatus.trackPeaks]). */
    val engineTrackIds: List<String> = emptyList(),
    val route: AudioRoute? = null,
    /** Saved profile for [route]; null until something is calibrated or chosen. */
    val profile: DeviceProfile? = null,
    val calibration: CalibrationUi = CalibrationUi.Idle,
) {
    val transport: TransportState get() = status.transport
    val isIdle: Boolean get() = transport == TransportState.IDLE

    fun trackPeak(trackId: String): Float =
        status.trackPeaks.getOrNull(engineTrackIds.indexOf(trackId)) ?: 0f
}

@OptIn(FlowPreview::class)
@HiltViewModel
class RecorderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    private val engine: AudioEngine,
    private val deviceMonitor: AudioDeviceMonitor,
    private val profiles: DeviceProfileRepository,
    private val calibrator: LatencyCalibrator,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val projectId: String = checkNotNull(savedStateHandle[ARG_PROJECT_ID])
    private val local = MutableStateFlow(RecorderUiState())

    // Serializes engine operations triggered by the UI and by device events.
    private val ops = Mutex()
    private var screenActive = false
    private var engineTrackIds: List<String> = emptyList()
    private var recordingTrackId: String? = null
    /** Profile key and calibration in effect when the current take started. */
    private var recordingCalibration: Pair<String, LatencyCalibration>? = null
    private var calibrationJob: Job? = null

    val uiState: StateFlow<RecorderUiState> =
        combine(local, repository.observe(projectId), engine.status, profiles.profiles) { l, project, status, all ->
            l.copy(project = project, status = status, profile = l.route?.let { r -> all.firstOrNull { it.key == r.key } })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecorderUiState())

    init {
        // Device plugged/unplugged: finish any take and reopen on the new route.
        viewModelScope.launch {
            deviceMonitor.devices.drop(1).debounce(DEVICE_DEBOUNCE_MS).collect {
                if (screenActive) reopen(RecorderNotice.DEVICE_CHANGED)
            }
        }
        // A stream closed by the system (device lost) without a device-list change.
        viewModelScope.launch {
            engine.status.map { it.outputDisconnected || it.inputDisconnected }
                .distinctUntilChanged().filter { it }
                .collect { if (screenActive) reopen(RecorderNotice.DEVICE_CHANGED) }
        }
        // Stop playback at the end of the project.
        viewModelScope.launch {
            engine.status.collect { s ->
                val project = uiState.value.project ?: return@collect
                if (s.transport == TransportState.PLAYING &&
                    s.positionFrames > project.lengthFrames + project.sampleRate / 2
                ) stop()
            }
        }
    }

    fun onStart() {
        screenActive = true
        viewModelScope.launch { ops.withLock { openEngine() } }
    }

    fun onStop() {
        screenActive = false
        viewModelScope.launch { ops.withLock { finishTake(engine.stop(), interrupted = true); engine.close() } }
    }

    override fun onCleared() {
        // viewModelScope is cancelled; make sure the take is saved and streams are released.
        MainScope().launch { ops.withLock { finishTake(engine.stop(), interrupted = true); engine.close() } }
    }

    fun togglePlay() {
        viewModelScope.launch {
            ops.withLock {
                if (engine.status.value.transport == TransportState.IDLE) {
                    engine.setMetronome(currentProject().metronome, 0)
                    engine.play(0)
                } else {
                    finishTake(engine.stop(), interrupted = false)
                }
            }
        }
    }

    fun stop() {
        viewModelScope.launch { ops.withLock { finishTake(engine.stop(), interrupted = false) } }
    }

    fun recordNewTrack() {
        viewModelScope.launch {
            ops.withLock {
                if (engine.status.value.transport != TransportState.IDLE) return@withLock
                val project = currentProject()
                val trackId = UUID.randomUUID().toString()
                val takeId = UUID.randomUUID().toString()
                val file = repository.takeAudioFile(project.id, trackId, "$takeId.wav")
                val mode = effectiveChannelMode()
                val channels = if (mode == RecordChannelMode.STEREO) 2 else 1
                val take = Take(
                    id = takeId,
                    audioFile = file.name,
                    channelCount = channels,
                    lengthFrames = Take.LENGTH_UNKNOWN,
                    recordStartFrame = 0,
                    autoOffsetFrames = 0,
                    alignment = AlignmentInfo(AlignmentMethod.TIMESTAMPS, 0.0),
                    createdAtMillis = System.currentTimeMillis(),
                )
                val name = "${TRACK_NAME_PREFIX}${project.tracks.size + 1}"
                // Persist the take before recording, so a crash can recover it (SPEC 2.1).
                repository.update(project.id) {
                    it.copy(tracks = it.tracks + Track(trackId, name, listOf(take), takeId))
                }
                val route = local.value.route
                val calibration = route?.let { profiles.get(it.key)?.calibration }
                recordingCalibration = if (route != null && calibration != null) route.key to calibration else null
                // Count-in: the transport starts before project frame 0, so the take begins
                // with the count-in clicks in the headphones (never in the recording).
                val countIn = project.metronome.countInFrames(project.sampleRate)
                engine.setMetronome(project.metronome, countIn)
                if (engine.record(file, -countIn, mode)) {
                    recordingTrackId = trackId
                } else {
                    Log.e(LOG_TAG, "Could not start recording")
                    removeTrack(trackId)
                    local.update { it.copy(notice = RecorderNotice.RECORD_FAILED) }
                }
            }
        }
    }

    fun toggleMute(trackId: String) {
        viewModelScope.launch {
            val updated = repository.update(projectId) { p ->
                p.copy(tracks = p.tracks.map { if (it.id == trackId) it.copy(muted = !it.muted) else it })
            }
            val index = engineTrackIds.indexOf(trackId)
            val muted = updated.tracks.firstOrNull { it.id == trackId }?.muted ?: return@launch
            val track = updated.tracks.firstOrNull { it.id == trackId } ?: return@launch
            if (index >= 0) engine.setTrackGain(index, track.playbackGain)
        }
    }

    /** Live fader move: changes the engine gain only (no disk write). */
    fun previewTrackVolume(trackId: String, db: Double) {
        val index = engineTrackIds.indexOf(trackId)
        val muted = uiState.value.project?.tracks?.firstOrNull { it.id == trackId }?.muted ?: return
        if (index < 0) return
        viewModelScope.launch {
            engine.setTrackGain(index, if (muted) 0f else Levels.dbToLinear(db).toFloat())
        }
    }

    /** Fader released: persists the volume. */
    fun setTrackVolume(trackId: String, db: Double) {
        viewModelScope.launch {
            val updated = repository.update(projectId) { p ->
                p.copy(tracks = p.tracks.map {
                    if (it.id == trackId) it.copy(volumeDb = db.coerceIn(Levels.MIN_DB, Levels.MAX_DB)) else it
                })
            }
            val index = engineTrackIds.indexOf(trackId)
            val track = updated.tracks.firstOrNull { it.id == trackId } ?: return@launch
            if (index >= 0) engine.setTrackGain(index, track.playbackGain)
        }
    }

    /** Manual nudge on top of the automatic alignment (SPEC 2.2). Only while idle. */
    fun setManualOffsetMs(trackId: String, ms: Double) {
        viewModelScope.launch {
            ops.withLock {
                if (engine.status.value.transport != TransportState.IDLE) return@withLock
                val project = currentProject()
                val frames = AlignmentMath.msToFrames(ms, project.sampleRate)
                val updated = repository.update(project.id) { p ->
                    p.copy(tracks = p.tracks.map { track ->
                        if (track.id != trackId) track else track.copy(takes = track.takes.map { take ->
                            if (take.id == track.activeTakeId) take.copy(manualOffsetFrames = frames) else take
                        })
                    })
                }
                if (engine.status.value.isOpen) loadTracks(updated)
            }
        }
    }

    fun updateMetronome(transform: (MetronomeSettings) -> MetronomeSettings) {
        viewModelScope.launch {
            val updated = repository.update(projectId) { p -> p.copy(metronome = transform(p.metronome).normalized()) }
            // While recording, keep the count-in window that the take started with.
            val countIn = if (engine.status.value.transport == TransportState.RECORDING) {
                updated.metronome.countInFrames(updated.sampleRate)
            } else 0L
            engine.setMetronome(updated.metronome, countIn)
        }
    }

    private val tapTimes = ArrayDeque<Long>()

    fun tapTempo(nowMs: Long) {
        if (tapTimes.isNotEmpty() && nowMs - tapTimes.last() > TAP_RESET_MS) tapTimes.clear()
        tapTimes.addLast(nowMs)
        while (tapTimes.size > 5) tapTimes.removeFirst()
        val bpm = MetronomeSettings.bpmFromTaps(tapTimes.toList()) ?: return
        updateMetronome { it.copy(bpm = Math.round(bpm * 10) / 10.0) }
    }

    fun deleteTrack(trackId: String) {
        viewModelScope.launch {
            ops.withLock {
                if (engine.status.value.transport != TransportState.IDLE) return@withLock
                removeTrack(trackId)
                loadTracks(currentProject())
            }
        }
    }

    fun setChannelMode(mode: RecordChannelMode) {
        local.update { it.copy(channelMode = mode) }
        viewModelScope.launch {
            val profile = saveProfile { it.copy(inputChannel = mode.toInputChannel()) }
            applyInputProcessing(profile)
        }
    }

    /** Live input gain move (no disk write). */
    fun previewInputGain(db: Double) {
        viewModelScope.launch {
            val profile = local.value.route?.let { profiles.get(it.key) }
            applyInputProcessing((profile ?: newProfile() ?: return@launch).copy(inputGainDb = db))
        }
    }

    fun setInputGain(db: Double) {
        viewModelScope.launch {
            val clamped = db.coerceIn(DeviceProfile.MIN_INPUT_GAIN_DB, DeviceProfile.MAX_INPUT_GAIN_DB)
            applyInputProcessing(saveProfile { it.copy(inputGainDb = clamped) })
        }
    }

    fun setMonitoring(on: Boolean) {
        viewModelScope.launch {
            if (on && local.value.route?.monitoringAllowed != true) return@launch
            applyInputProcessing(saveProfile { it.copy(monitoring = on) })
        }
    }

    fun previewMonitorGain(db: Double) {
        viewModelScope.launch {
            val profile = local.value.route?.let { profiles.get(it.key) } ?: return@launch
            applyInputProcessing(profile.copy(monitorGainDb = db))
        }
    }

    fun setMonitorGain(db: Double) {
        viewModelScope.launch {
            applyInputProcessing(saveProfile { it.copy(monitorGainDb = db.coerceIn(Levels.MIN_DB, 12.0)) })
        }
    }

    // --- calibration (SPEC 2.4) ----------------------------------------------------------

    fun openCalibration() {
        if (local.value.calibration == CalibrationUi.Idle) local.update { it.copy(calibration = CalibrationUi.Choosing) }
    }

    fun startCalibration(task: CalibrationTask) {
        if (local.value.calibration is CalibrationUi.Running) return
        calibrationJob = viewModelScope.launch {
            ops.withLock {
                val status = engine.status.value
                if (!status.isOpen || status.transport != TransportState.IDLE || local.value.route == null) {
                    local.update { it.copy(calibration = CalibrationUi.Finished(task, CalibrationOutcome.Failed(CalibrationFailure.INTERRUPTED))) }
                    return@withLock
                }
                local.update { it.copy(calibration = CalibrationUi.Running(task, 0f)) }
                val outcome = try {
                    measure(task)
                } finally {
                    // Put playback back the way it was (also after a cancel).
                    withContext(NonCancellable) {
                        val project = currentProject()
                        if (engine.status.value.isOpen) {
                            loadTracks(project)
                            engine.setMetronome(project.metronome, 0)
                            applyInputProcessing(local.value.route?.let { profiles.get(it.key) })
                        }
                    }
                }
                local.update { it.copy(calibration = CalibrationUi.Finished(task, outcome)) }
            }
        }
    }

    fun cancelCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        local.update { it.copy(calibration = CalibrationUi.Idle) }
    }

    fun saveCalibration() {
        val finished = local.value.calibration as? CalibrationUi.Finished ?: return
        val outcome = finished.outcome as? CalibrationOutcome.Calibrated ?: return
        viewModelScope.launch {
            saveProfile { it.copy(calibration = outcome.calibration) }
            Log.i(LOG_TAG, "Calibration saved for ${local.value.route?.key}: ${outcome.calibration}")
            local.update { it.copy(calibration = CalibrationUi.Finished(finished.task, outcome.copy(saved = true))) }
        }
    }

    fun closeCalibration() = local.update { it.copy(calibration = CalibrationUi.Idle) }

    private suspend fun measure(task: CalibrationTask): CalibrationOutcome {
        val status = engine.status.value
        val sampleRate = status.sampleRate
        val profile = local.value.route?.let { profiles.get(it.key) }
        // Silence everything the measurement must not hear: tracks and software monitoring.
        engine.setTracks(emptyList())
        engineTrackIds = emptyList()
        engine.setInputProcessing(Levels.dbToLinear(profile?.inputGainDb ?: 0.0).toFloat(), 0f, RecordChannelMode.FIRST)
        val mode = if (status.inputChannels >= 2) RecordChannelMode.STEREO else RecordChannelMode.FIRST
        val kind = if (task == CalibrationTask.TAP) MeasurementKind.TAPS else MeasurementKind.IMPULSES
        val file = File(context.cacheDir, "calibration.wav")
        val result = calibrator.measure(kind, file, sampleRate, mode) { progress ->
            local.update { s ->
                val running = s.calibration as? CalibrationUi.Running ?: return@update s
                s.copy(calibration = running.copy(progress = progress))
            }
        }
        val done = result as? MeasurementResult.Done
            ?: return CalibrationOutcome.Failed(CalibrationFailure.INTERRUPTED)
        val a = done.analysis
        val now = System.currentTimeMillis()

        if (task == CalibrationTask.LEAK) {
            val detected = a.lagFrames != null && a.coherence >= LatencyCalibrator.MIN_LEAK_COHERENCE &&
                a.accepted * 2 >= a.attempts
            val leak = LeakTestResult(
                detected = detected,
                levelDb = if (detected) a.levelDb else null,
                delayMs = if (detected) done.roundTripMs else null,
                testedAtMillis = now,
            )
            saveProfile { it.copy(leak = leak) }
            return CalibrationOutcome.Leak(leak)
        }

        val residual = done.residualMs
        val roundTrip = done.roundTripMs
        val failure = when {
            residual == null || roundTrip == null -> CalibrationFailure.NO_SIGNAL
            kind == MeasurementKind.IMPULSES && a.coherence < LatencyCalibrator.MIN_CALIBRATION_COHERENCE ->
                CalibrationFailure.NO_SIGNAL
            kind == MeasurementKind.TAPS && a.accepted < LatencyCalibrator.MIN_TAPS -> CalibrationFailure.NO_SIGNAL
            a.accepted * 2 < a.attempts -> CalibrationFailure.INCONSISTENT
            else -> null
        }
        if (failure != null) return CalibrationOutcome.Failed(failure)
        return CalibrationOutcome.Calibrated(
            LatencyCalibration(
                method = task.method!!,
                residualMs = residual!!,
                roundTripMs = roundTrip!!,
                spreadMs = done.spreadMs,
                accepted = a.accepted,
                attempts = a.attempts,
                sampleRate = sampleRate,
                measuredAtMillis = now,
            ),
            saved = false,
        )
    }

    fun deleteProfileCalibration() {
        viewModelScope.launch { saveProfile { it.copy(calibration = null) } }
    }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    // --- internals (call with `ops` held) -------------------------------------------------

    private suspend fun currentProject(): Project = repository.observe(projectId).filterNotNull().first()

    private fun newProfile(): DeviceProfile? {
        val route = local.value.route ?: return null
        return DeviceProfile(
            key = route.key,
            outputLabel = DeviceProfile.deviceLabel(route.output),
            inputLabel = DeviceProfile.deviceLabel(route.input),
            monitoring = false, // SPEC 2.4: off by default
        )
    }

    /** Creates or updates the profile of the current route. */
    private suspend fun saveProfile(transform: (DeviceProfile) -> DeviceProfile): DeviceProfile? {
        val route = local.value.route ?: return null
        val sampleRate = engine.status.value.sampleRate
        return profiles.upsert(route.key, { newProfile()!! }) { current ->
            transform(current).copy(
                outputLabel = DeviceProfile.deviceLabel(route.output),
                inputLabel = DeviceProfile.deviceLabel(route.input),
                lastSampleRate = if (sampleRate > 0) sampleRate else current.lastSampleRate,
            )
        }
    }

    /** Sends input gain, monitoring and monitored channels to the engine. */
    private suspend fun applyInputProcessing(profile: DeviceProfile?) {
        val route = local.value.route
        val gain = Levels.dbToLinear(profile?.inputGainDb ?: 0.0).toFloat()
        val monitor = if (profile?.monitoring == true && route?.monitoringAllowed == true) {
            Levels.dbToLinear(profile.monitorGainDb).toFloat()
        } else 0f
        engine.setInputProcessing(gain, monitor, effectiveChannelMode())
    }

    private fun resolveRoute(status: EngineStatus, preferredInput: AudioDevice?): AudioRoute {
        val devices = deviceMonitor.devices.value
        val output = devices.firstOrNull { !it.isInput && it.id == status.outputDeviceId }
        val input = devices.firstOrNull { it.isInput && it.id == status.inputDeviceId } ?: preferredInput
        return AudioRoute(DeviceProfile.routeKey(output, input), output, input)
    }

    private fun effectiveChannelMode(): RecordChannelMode {
        val channels = engine.status.value.inputChannels
        val mode = local.value.channelMode
        return if (channels < 2) RecordChannelMode.FIRST else mode
    }

    private suspend fun openEngine() {
        val project = currentProject()
        val input = AudioRouting.preferredInput(deviceMonitor.devices.value)
        val config = EngineConfig(
            sampleRate = project.sampleRate,
            inputChannelCount = AudioRouting.inputChannelCount(input),
            inputPreset = AudioRouting.inputPreset(deviceMonitor.systemInfo()),
            inputDeviceId = input?.id ?: 0,
        )
        when (val result = engine.open(config)) {
            is EngineOpenResult.Failed -> local.update { it.copy(engineError = result.reason) }
            EngineOpenResult.Opened -> {
                // The route is known once the first status of the open streams arrives.
                val status = withTimeoutOrNull(STATUS_WAIT_MS) { engine.status.first { it.isOpen } }
                    ?: engine.status.value
                val route = resolveRoute(status, input)
                val profile = profiles.get(route.key)
                Log.i(LOG_TAG, "Route ${route.key}: profile ${if (profile == null) "none" else "loaded"}" +
                    (profile?.calibration?.let { ", residual ${"%.2f".format(it.residualMs)} ms (${it.method})" } ?: ""))
                local.update {
                    it.copy(
                        engineError = null,
                        inputDevice = input,
                        freeBytes = repository.freeBytes(),
                        route = route,
                        channelMode = profile?.inputChannel?.toRecordMode() ?: it.channelMode,
                    )
                }
                applyInputProcessing(profile)
                loadTracks(project)
                engine.setMetronome(project.metronome, 0)
            }
        }
    }

    private suspend fun reopen(notice: RecorderNotice) {
        ops.withLock {
            val interrupted = recordingTrackId != null
            finishTake(engine.stop(), interrupted = true)
            engine.close()
            openEngine()
            local.update { it.copy(notice = if (interrupted) RecorderNotice.TAKE_INTERRUPTED else notice) }
        }
    }

    private suspend fun loadTracks(project: Project) {
        val playable = project.tracks.filter { (it.activeTake?.lengthFrames ?: 0) > 0 }
        engineTrackIds = playable.map { it.id }
        engine.setTracks(
            playable.map { track ->
                val take = track.activeTake!!
                PlaybackTrack(
                    file = repository.takeAudioFile(project.id, track.id, take.audioFile),
                    startFrame = take.startFrame,
                    gain = track.playbackGain,
                )
            },
        )
        local.update { it.copy(freeBytes = repository.freeBytes(), engineTrackIds = engineTrackIds) }
    }

    /** Saves the take that was being recorded (if any) with its automatic alignment. */
    private suspend fun finishTake(result: RecordingResult?, interrupted: Boolean) {
        val trackId = recordingTrackId ?: return
        recordingTrackId = null
        // Nothing past the count-in (stopped before project frame 0): drop the take.
        if (result == null || result.frames <= 0 || result.startPosition + result.frames <= 0) {
            removeTrack(trackId)
            return
        }
        val project = currentProject()
        val calibration = recordingCalibration
        recordingCalibration = null
        val residualFrames = calibration?.second?.let { it.residualMs * project.sampleRate / 1000.0 } ?: 0.0
        val alignment = result.alignment(
            project.sampleRate, residualFrames, calibration?.first, calibration?.second?.method,
        )
        Log.i(
            LOG_TAG,
            "Take saved: ${result.frames} frames, offset=${alignment.autoOffsetFrames} frames " +
                "(${alignment.info.method}, residual ${"%.1f".format(residualFrames)}), interrupted=$interrupted",
        )
        val updated = repository.update(project.id) { p ->
            p.copy(tracks = p.tracks.map { track ->
                if (track.id != trackId) track else track.copy(takes = track.takes.map { take ->
                    if (take.id != track.activeTakeId) take else take.copy(
                        channelCount = result.channels,
                        lengthFrames = result.frames,
                        recordStartFrame = result.startPosition,
                        autoOffsetFrames = alignment.autoOffsetFrames,
                        alignment = alignment.info,
                    )
                })
            })
        }
        if (result.xrunsDuring > 0 || result.droppedFrames > 0) {
            local.update { it.copy(notice = RecorderNotice.TAKE_GLITCHED) }
        }
        if (engine.status.value.isOpen) loadTracks(updated)
    }

    private suspend fun removeTrack(trackId: String) {
        val project = currentProject()
        project.tracks.firstOrNull { it.id == trackId }?.takes?.forEach { take ->
            repository.takeAudioFile(project.id, trackId, take.audioFile).delete()
        }
        repository.update(project.id) { p -> p.copy(tracks = p.tracks.filterNot { it.id == trackId }) }
    }

    companion object {
        const val ARG_PROJECT_ID = "projectId"
        private const val DEVICE_DEBOUNCE_MS = 400L
        private const val TAP_RESET_MS = 2_000L
        private const val STATUS_WAIT_MS = 1_000L
        // Track names are user data (renaming comes later); the number is what matters.
        private const val TRACK_NAME_PREFIX = "#"
    }
}

private fun RecordChannelMode.toInputChannel() = when (this) {
    RecordChannelMode.FIRST -> InputChannel.FIRST
    RecordChannelMode.SECOND -> InputChannel.SECOND
    RecordChannelMode.STEREO -> InputChannel.STEREO
}

private fun InputChannel.toRecordMode() = when (this) {
    InputChannel.FIRST -> RecordChannelMode.FIRST
    InputChannel.SECOND -> RecordChannelMode.SECOND
    InputChannel.STEREO -> RecordChannelMode.STEREO
}
