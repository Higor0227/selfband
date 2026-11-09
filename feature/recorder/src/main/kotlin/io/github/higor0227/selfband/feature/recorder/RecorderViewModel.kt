// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import io.github.higor0227.selfband.core.model.LayoutTemplate
import io.github.higor0227.selfband.core.model.LayerEditing
import io.github.higor0227.selfband.core.project.LayoutTemplateRepository
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.higor0227.selfband.core.audio.AudioDeviceMonitor
import io.github.higor0227.selfband.core.audio.AudioEngine
import io.github.higor0227.selfband.core.audio.AudioRoute
import io.github.higor0227.selfband.core.audio.calibration.CalibrationFailure
import io.github.higor0227.selfband.core.audio.calibration.CalibrationOutcome
import io.github.higor0227.selfband.core.audio.calibration.CalibrationTask
import io.github.higor0227.selfband.core.audio.calibration.RouteCalibrator
import io.github.higor0227.selfband.core.audio.AudioRouting
import io.github.higor0227.selfband.core.audio.EngineConfig
import io.github.higor0227.selfband.core.audio.EngineOpenResult
import io.github.higor0227.selfband.core.audio.EngineStatus
import io.github.higor0227.selfband.core.audio.PlaybackTracks
import io.github.higor0227.selfband.core.audio.RecordChannelMode
import io.github.higor0227.selfband.core.audio.RecordingResult
import io.github.higor0227.selfband.core.audio.TakeAudio
import io.github.higor0227.selfband.core.audio.TransportState
import io.github.higor0227.selfband.core.audio.Waveforms
import io.github.higor0227.selfband.core.audio.alignment
import io.github.higor0227.selfband.core.model.AlignmentInfo
import io.github.higor0227.selfband.core.model.AlignmentMath
import io.github.higor0227.selfband.core.model.AlignmentMethod
import io.github.higor0227.selfband.core.model.AspectRatio
import io.github.higor0227.selfband.core.model.AudioDevice
import io.github.higor0227.selfband.core.model.AudioDeviceType
import io.github.higor0227.selfband.core.model.CameraLens
import io.github.higor0227.selfband.core.model.CameraSettings
import io.github.higor0227.selfband.core.model.VideoResolution
import io.github.higor0227.selfband.core.model.DeviceProfile
import io.github.higor0227.selfband.core.model.InputChannel
import io.github.higor0227.selfband.core.model.LatencyCalibration
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.Levels
import io.github.higor0227.selfband.core.model.MetronomeSettings
import io.github.higor0227.selfband.core.model.MixerSettings
import io.github.higor0227.selfband.core.model.Mixing
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.model.Proxies
import io.github.higor0227.selfband.core.model.ReverbSettings
import io.github.higor0227.selfband.core.model.Take
import io.github.higor0227.selfband.core.model.TakeVideo
import io.github.higor0227.selfband.core.model.Track
import io.github.higor0227.selfband.core.model.TrackEq
import io.github.higor0227.selfband.core.model.Trim
import io.github.higor0227.selfband.core.model.Waveform
import io.github.higor0227.selfband.core.camera.CameraCapabilities
import io.github.higor0227.selfband.core.camera.CameraControls
import io.github.higor0227.selfband.core.camera.CameraState
import io.github.higor0227.selfband.core.camera.VideoConfig
import io.github.higor0227.selfband.core.camera.VideoCamera
import io.github.higor0227.selfband.core.camera.VideoRecording
import io.github.higor0227.selfband.core.project.AppSettingsRepository
import io.github.higor0227.selfband.core.project.DeviceProfileRepository
import io.github.higor0227.selfband.core.project.ProjectRepository
import io.github.higor0227.selfband.core.video.CanvasPreview
import io.github.higor0227.selfband.core.video.CanvasPreviewFactory
import io.github.higor0227.selfband.core.video.CanvasScene
import io.github.higor0227.selfband.core.video.CanvasScenes
import io.github.higor0227.selfband.core.video.ProxyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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

sealed interface CalibrationUi {
    data object Idle : CalibrationUi
    /** The user is choosing a method (dialog open, nothing running). */
    data object Choosing : CalibrationUi
    data class Running(val task: CalibrationTask, val progress: Float) : CalibrationUi
    data class Finished(val task: CalibrationTask, val outcome: CalibrationOutcome) : CalibrationUi
}

enum class CameraLockKind { EXPOSURE, WHITE_BALANCE, FOCUS }

/** A still of a previous track shown over the preview to repeat the framing (SPEC 2.2). */
data class GhostUi(val trackId: String, val bitmap: Bitmap, val mirrored: Boolean)

enum class SyncCheckError { NO_PEAK, NO_TIMESTAMP, OUT_OF_RANGE, DECODE }

/** One video frame around the clap; [offset] 0 is where the clap sound falls. */
data class SyncFrame(val offset: Int, val index: Int, val bitmap: Bitmap?)

/** Clap test (SPEC 4, phase 2): video frames around the loudest sound of a take. */
data class SyncCheckUi(
    val trackId: String,
    val loading: Boolean = true,
    val frames: List<SyncFrame> = emptyList(),
    val frameMs: Double = 0.0,
    val videoOffsetMs: Double = 0.0,
    val error: SyncCheckError? = null,
)

/** What the panel above the tracks shows. */
enum class PanelMode { CAMERA, CANVAS, MIXER }

data class RecorderUiState(
    val project: Project? = null,
    /** Layout templates saved by the user (SPEC 2.6). */
    val userTemplates: List<LayoutTemplate> = emptyList(),
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
    val camera: CameraState = CameraState(),
    val cameraPermission: Boolean = false,
    /** Record video with new tracks (off = audio-only tracks, SPEC 2.2). */
    val videoEnabled: Boolean = true,
    val availableLenses: List<CameraLens> = emptyList(),
    /** Capabilities of the camera in use (formats, stabilization, locks). */
    val capabilities: CameraCapabilities? = null,
    val ghost: GhostUi? = null,
    val syncCheck: SyncCheckUi? = null,
    /** Track receiving the take being recorded (new track or a re-take). */
    val recordingTrackId: String? = null,
    val panelMode: PanelMode = PanelMode.CAMERA,
    /** Where playback starts and what the canvas shows while idle (project frames). */
    val playheadFrames: Long = 0,
    /** Peak overviews of the active takes, by take id (loaded in the background). */
    val waveforms: Map<String, Waveform> = emptyMap(),
) {
    val cameraSettings: CameraSettings get() = project?.camera ?: CameraSettings()
    val transport: TransportState get() = status.transport
    val isIdle: Boolean get() = transport == TransportState.IDLE

    /** Peak of [trackId] in [live] (the live status, not [status], which has no meters). */
    fun trackPeak(live: EngineStatus, trackId: String): Float =
        live.trackPeaks.getOrNull(engineTrackIds.indexOf(trackId)) ?: 0f
}

@OptIn(FlowPreview::class)
@HiltViewModel
class RecorderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProjectRepository,
    private val engine: AudioEngine,
    private val deviceMonitor: AudioDeviceMonitor,
    private val profiles: DeviceProfileRepository,
    private val routeCalibrator: RouteCalibrator,
    private val layoutTemplates: LayoutTemplateRepository,
    private val camera: VideoCamera,
    private val waveforms: Waveforms,
    canvasFactory: CanvasPreviewFactory,
    private val proxies: ProxyGenerator,
    settings: AppSettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Live canvas (SPEC 2.6/3.5); the screen attaches its surface. Released in onCleared. */
    val canvasPreview: CanvasPreview = canvasFactory.create()
    @Volatile private var playhead = 0L

    val projectId: String = checkNotNull(savedStateHandle[ARG_PROJECT_ID])
    private val local = MutableStateFlow(RecorderUiState())

    // Serializes engine operations triggered by the UI and by device events.
    private val ops = Mutex()
    // Serializes camera reopenings (lens, format) so quick taps do not interleave.
    private val cameraOps = Mutex()
    private var screenActive = false
    private var engineTrackIds: List<String> = emptyList()
    private var recordingTrackId: String? = null
    private var recordingTakeId: String? = null
    /** True if the take being recorded created its track (dropping it removes the track). */
    private var recordingNewTrack = false
    /** Active take of the track before a re-take, restored if the re-take is dropped. */
    private var previousActiveTakeId: String? = null
    /** Profile key and calibration in effect when the current take started. */
    private var recordingCalibration: Pair<String, LatencyCalibration>? = null
    private var calibrationJob: Job? = null
    /** True while the camera is encoding the current take. */
    private var videoRecording = false

    val uiState: StateFlow<RecorderUiState> =
        combine(
            local,
            repository.observe(projectId),
            engine.status.map { it.withoutLiveValues() }.distinctUntilChanged(),
            profiles.profiles,
            camera.state,
        ) { l, project, status, all, cam ->
            l.copy(
                project = project,
                status = status,
                profile = l.route?.let { r -> all.firstOrNull { it.key == r.key } },
                camera = cam,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecorderUiState())

    /** Position and meters as they change; see [LocalLiveStatus]. */
    val liveStatus: StateFlow<EngineStatus> = engine.status

    init {
        viewModelScope.launch {
            layoutTemplates.templates.collect { t -> local.update { it.copy(userTemplates = t) } }
        }
        // Canvas: the video follows the audio clock while the transport runs, else the playhead.
        canvasPreview.setPosition { timeNanos ->
            val s = engine.status.value
            val clock = s.presentationClock
            if (s.transport != TransportState.IDLE && clock != null) clock.positionAt(timeNanos) else playhead.toDouble()
        }
        // Rebuilt when a proxy is finished too, so the preview can switch to it (SPEC 3.5).
        viewModelScope.launch {
            combine(repository.observe(projectId).filterNotNull(), proxies.state.map { it.finished }.distinctUntilChanged()) { p, _ -> p }
                .map(::sceneOf).flowOn(Dispatchers.IO).distinctUntilChanged()
                .collect { canvasPreview.setScene(it) }
        }
        viewModelScope.launch {
            settings.settings.map { it.proxyMode }.distinctUntilChanged().collect { canvasPreview.setProxyMode(it) }
        }
        // Proxies of new takes are made in the background, never while recording.
        viewModelScope.launch {
            repository.observe(projectId).filterNotNull().collect { p ->
                proxies.request(p) { trackId, name -> repository.takeAudioFile(p.id, trackId, name) }
            }
        }
        viewModelScope.launch {
            engine.status.map { it.transport == TransportState.RECORDING }.distinctUntilChanged()
                .collect { proxies.setPaused(it) }
        }
        // Ghost: load a still of the chosen track's active take whenever the choice changes.
        viewModelScope.launch {
            repository.observe(projectId).filterNotNull().map { p ->
                val id = p.camera.ghostTrackId ?: return@map null
                val track = p.tracks.firstOrNull { it.id == id } ?: return@map null
                // The active take's video; during a re-take (new take still empty), the
                // newest earlier take with video, so the framing can be repeated.
                val video = track.activeTake?.video ?: track.takes.lastOrNull { it.video != null }?.video
                    ?: return@map null
                Triple(track.id, repository.takeAudioFile(p.id, track.id, video.file), video)
            }.distinctUntilChanged().collectLatest { ghost ->
                val ui = ghost?.let { (trackId, file, video) ->
                    withContext(Dispatchers.IO) { loadGhost(file, video) }?.let { GhostUi(trackId, it, video.previewMirrored) }
                }
                local.update { it.copy(ghost = ui) }
            }
        }
        // Waveforms of the active takes (timeline, SPEC 2.5): load what is missing, drop the rest.
        viewModelScope.launch {
            repository.observe(projectId).filterNotNull().map { p ->
                p.tracks.mapNotNull { track ->
                    val take = track.activeTake?.takeIf { it.lengthFrames > 0 } ?: return@mapNotNull null
                    take.id to repository.takeAudioFile(p.id, track.id, take.audioFile)
                }
            }.distinctUntilChanged().collectLatest { wanted ->
                val ids = wanted.map { it.first }.toSet()
                local.update { s -> s.copy(waveforms = s.waveforms.filterKeys { it in ids }) }
                for ((takeId, file) in wanted) {
                    if (local.value.waveforms.containsKey(takeId)) continue
                    val waveform = waveforms.load(file) ?: continue
                    local.update { s -> s.copy(waveforms = s.waveforms + (takeId to waveform)) }
                }
            }
        }
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
        // Stop playback at the trim end, or shortly after the end of the project (tails).
        viewModelScope.launch {
            engine.status.collect { s ->
                val project = uiState.value.project ?: return@collect
                val end = if (project.trimEnd < project.lengthFrames) project.trimEnd
                else project.lengthFrames + project.sampleRate / 2
                if (s.transport == TransportState.PLAYING && s.positionFrames >= end) stop()
            }
        }
    }

    fun onStart() {
        screenActive = true
        canvasPreview.setPaused(false)
        viewModelScope.launch { ops.withLock { openEngine() } }
        viewModelScope.launch { openCamera() }
    }

    fun onStop() {
        screenActive = false
        canvasPreview.setPaused(true)
        viewModelScope.launch {
            ops.withLock { finishTake(engine.stop(), interrupted = true); engine.close() }
            camera.close()
        }
    }

    override fun onCleared() {
        // viewModelScope is cancelled; make sure the take is saved and streams are released.
        MainScope().launch {
            ops.withLock { finishTake(engine.stop(), interrupted = true); engine.close() }
            camera.close()
        }
        canvasPreview.release()
    }

    // --- canvas (SPEC 2.6, 3.5) ----------------------------------------------------------

    fun setPanelMode(mode: PanelMode) = local.update { it.copy(panelMode = mode) }

    /** Canvas format; layers follow the template's variant for it and keep their crops. */
    fun setAspectRatio(ratio: AspectRatio) {
        viewModelScope.launch { repository.update(projectId) { it.copy(aspectRatio = ratio) } }
    }

    fun setLayoutTemplate(templateId: String) {
        viewModelScope.launch {
            repository.update(projectId) { it.copy(canvas = it.canvas.copy(templateId = templateId)) }
        }
    }

    /** Applies a saved template as a free layout; the project keeps no link to it. */
    fun applyUserTemplate(templateId: String) {
        val template = layoutTemplates.templates.value.firstOrNull { it.id == templateId } ?: return
        viewModelScope.launch {
            repository.update(projectId) { it.copy(canvas = LayerEditing.applyTemplate(it, template)) }
            Log.i(LOG_TAG, "Layout template applied: $templateId")
        }
    }

    fun renameUserTemplate(templateId: String, name: String) {
        viewModelScope.launch { layoutTemplates.rename(templateId, name) }
    }

    fun deleteUserTemplate(templateId: String) {
        viewModelScope.launch { layoutTemplates.delete(templateId) }
    }

    /** Global trim (SPEC 2.6), clamped to the project; non-destructive. */
    fun setTrim(startFrame: Long, endFrame: Long) {
        viewModelScope.launch {
            repository.update(projectId) { p ->
                val (start, end) = Trim.normalized(p, startFrame, endFrame)
                p.copy(trimStartFrame = start, trimEndFrame = end)
            }
        }
    }

    fun clearTrim() {
        viewModelScope.launch { repository.update(projectId) { it.copy(trimStartFrame = 0, trimEndFrame = null) } }
    }

    fun setPlayhead(frames: Long) {
        val max = uiState.value.project?.lengthFrames ?: 0L
        val clamped = frames.coerceIn(0L, max)
        playhead = clamped
        local.update { it.copy(playheadFrames = clamped) }
    }

    private fun sceneOf(project: Project): CanvasScene =
        CanvasScenes.build(project, { trackId, name -> repository.takeAudioFile(project.id, trackId, name) }, { name -> repository.projectMediaFile(project.id, name) })

    // --- camera (SPEC 2.2, 3.4) ----------------------------------------------------------

    fun setCameraPermission(granted: Boolean) {
        if (local.value.cameraPermission == granted) return
        local.update { it.copy(cameraPermission = granted) }
        if (granted && screenActive) viewModelScope.launch { openCamera() }
    }

    fun setVideoEnabled(on: Boolean) {
        if (!uiState.value.isIdle) return
        local.update { it.copy(videoEnabled = on) }
        viewModelScope.launch { if (on) openCamera() else camera.close() }
    }

    fun switchLens() {
        val lenses = local.value.availableLenses
        if (lenses.size < 2) return
        updateCamera(reopen = true) { s -> s.copy(lens = lenses[(lenses.indexOf(s.lens) + 1) % lenses.size]) }
    }

    fun setResolution(resolution: VideoResolution) = updateCamera(reopen = true) { it.copy(resolution = resolution) }

    fun setFrameRate(frameRate: Int) = updateCamera(reopen = true) { it.copy(frameRate = frameRate) }

    fun setStabilization(on: Boolean) = updateCamera { it.copy(stabilization = on) }

    fun setGrid(on: Boolean) = updateCamera { it.copy(grid = on) }

    fun setGhostTrack(trackId: String?) = updateCamera { it.copy(ghostTrackId = trackId) }

    fun setGhostOpacity(opacity: Float) = updateCamera(whileRecording = true) { it.copy(ghostOpacity = opacity.coerceIn(0.05f, 0.95f)) }

    /**
     * Locks keep the values the camera is using right now (per lens), so every following
     * track looks the same (SPEC 2.2). Tapping again returns to automatic.
     */
    fun toggleLock(kind: CameraLockKind) {
        val values = camera.currentValues()
        updateCamera { s ->
            s.withLocks(s.lens) { l ->
                when (kind) {
                    CameraLockKind.EXPOSURE -> l.copy(exposure = if (l.exposure != null) null else values?.exposure)
                    CameraLockKind.WHITE_BALANCE -> l.copy(whiteBalance = if (l.whiteBalance != null) null else values?.whiteBalance)
                    CameraLockKind.FOCUS -> l.copy(focus = if (l.focus != null) null else values?.focus)
                }
            }
        }
    }

    private fun updateCamera(
        reopen: Boolean = false,
        whileRecording: Boolean = false,
        transform: (CameraSettings) -> CameraSettings,
    ) {
        if (!whileRecording && !uiState.value.isIdle) return
        viewModelScope.launch {
            val updated = repository.update(projectId) { it.copy(camera = transform(it.camera)) }
            if (reopen) {
                openCamera()
            } else {
                camera.setControls(controlsFor(updated.camera))
            }
        }
    }

    private fun controlsFor(settings: CameraSettings) =
        CameraControls(stabilization = settings.stabilization, locks = settings.locksFor(settings.lens))

    /** The chosen format if supported, else the closest one the camera offers. */
    private fun supportedFormat(caps: CameraCapabilities?, settings: CameraSettings): Pair<VideoResolution, Int> {
        val formats = caps?.formats.orEmpty()
        if (formats.isEmpty()) return settings.resolution to settings.frameRate
        val resolution = settings.resolution.takeIf { it in formats }
            ?: VideoResolution.P1080.takeIf { it in formats }
            ?: formats.keys.maxBy { it.width }
        val rates = formats.getValue(resolution)
        val frameRate = settings.frameRate.takeIf { it in rates }
            ?: 30.takeIf { it in rates }
            ?: rates.minBy { kotlin.math.abs(it - settings.frameRate) }
        return resolution to frameRate
    }

    private fun loadGhost(file: File, video: TakeVideo): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            // About one second in: past any first-frame exposure settling.
            val index = minOf(video.frameRate, video.frameCount - 1).coerceAtLeast(0)
            val full = retriever.getFrameAtIndex(index) ?: return null
            val w = minOf(GHOST_WIDTH, full.width)
            val h = (full.height.toLong() * w / full.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(full, w, h, true).also { if (it !== full) full.recycle() }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Ghost frame failed", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun setPreviewSurface(surface: android.view.Surface?) {
        viewModelScope.launch { camera.setPreviewSurface(surface) }
    }

    private suspend fun openCamera() = cameraOps.withLock {
        val l = local.value
        if (!l.cameraPermission || !l.videoEnabled || !screenActive) return@withLock
        val lenses = camera.availableLenses()
        var settings = currentProject().camera
        if (settings.lens !in lenses) {
            val fallback = lenses.firstOrNull() ?: return@withLock
            settings = repository.update(projectId) { it.copy(camera = it.camera.copy(lens = fallback)) }.camera
        }
        val caps = camera.capabilities(settings.lens)
        val (resolution, frameRate) = supportedFormat(caps, settings)
        local.update { it.copy(availableLenses = lenses, capabilities = caps) }
        camera.setControls(controlsFor(settings))
        val config = VideoConfig(resolution.width, resolution.height, frameRate, resolution.bitRate(frameRate))
        if (!camera.open(settings.lens, config)) {
            Log.w(LOG_TAG, "Camera ${settings.lens} could not be opened: ${camera.state.value.error}")
        }
    }

    /** Video manual offset, independent of the audio offset (SPEC 3.4). */
    fun setVideoOffsetMs(trackId: String, ms: Double) {
        viewModelScope.launch {
            ops.withLock {
                if (engine.status.value.transport != TransportState.IDLE) return@withLock
                updateActiveTake(trackId) { take ->
                    take.copy(video = take.video?.copy(manualOffsetMs = ms.coerceIn(-MAX_VIDEO_OFFSET_MS, MAX_VIDEO_OFFSET_MS)))
                }
            }
            if (local.value.syncCheck?.trackId == trackId) openSyncCheck(trackId)
        }
    }

    fun openSyncCheck(trackId: String) {
        viewModelScope.launch {
            val project = currentProject()
            val track = project.tracks.firstOrNull { it.id == trackId } ?: return@launch
            val take = track.activeTake ?: return@launch
            val video = take.video ?: return@launch
            // Keep the previous frames on screen while recomputing after an adjustment.
            local.update { s ->
                val previous = s.syncCheck?.takeIf { it.trackId == trackId }
                s.copy(syncCheck = previous?.copy(loading = true) ?: SyncCheckUi(trackId))
            }
            val result = withContext(Dispatchers.IO) { buildSyncCheck(project, track, take, video) }
            local.update { if (it.syncCheck?.trackId == trackId) it.copy(syncCheck = result) else it }
        }
    }

    /** The user saw the hands close on the frame at [offset]: move the video onto the clap. */
    fun pickSyncFrame(offset: Int) {
        val check = local.value.syncCheck ?: return
        if (offset == 0 || check.frameMs <= 0 || check.loading) return
        setVideoOffsetMs(check.trackId, check.videoOffsetMs - offset * check.frameMs)
    }

    fun closeSyncCheck() = local.update { it.copy(syncCheck = null) }

    private fun buildSyncCheck(project: Project, track: Track, take: Take, video: TakeVideo): SyncCheckUi {
        val sr = project.sampleRate
        val frameMs = 1000.0 / video.frameRate
        val base = SyncCheckUi(track.id, loading = false, frameMs = frameMs, videoOffsetMs = video.manualOffsetMs)
        val peak = TakeAudio.peakFrame(repository.takeAudioFile(project.id, track.id, take.audioFile))
            ?: return base.copy(error = SyncCheckError.NO_PEAK)
        val videoStart = video.startFrame(sr) ?: return base.copy(error = SyncCheckError.NO_TIMESTAMP)
        val clapPosition = take.startFrame + peak
        val center = Math.round((clapPosition - videoStart) / sr * video.frameRate).toInt()
        Log.i(LOG_TAG, "Sync check: clap at project frame $clapPosition, video frame $center of ${video.frameCount}")
        if (center !in 0 until video.frameCount) return base.copy(error = SyncCheckError.OUT_OF_RANGE)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(repository.takeAudioFile(project.id, track.id, video.file).absolutePath)
            val last = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                ?: video.frameCount) - 1
            val frames = (-SYNC_FRAMES..SYNC_FRAMES).mapNotNull { offset ->
                val index = center + offset
                if (index !in 0..last) return@mapNotNull null
                val full = runCatching { retriever.getFrameAtIndex(index) }.getOrNull()
                val small = full?.let {
                    val w = SYNC_THUMB_WIDTH
                    val h = (it.height.toLong() * w / it.width).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(it, w, h, true).also { s -> if (s !== it) it.recycle() }
                }
                SyncFrame(offset, index, small)
            }
            if (frames.none { it.bitmap != null }) base.copy(error = SyncCheckError.DECODE) else base.copy(frames = frames)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Sync check failed", e)
            base.copy(error = SyncCheckError.DECODE)
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun togglePlay() {
        viewModelScope.launch {
            ops.withLock {
                if (engine.status.value.transport == TransportState.IDLE) {
                    val project = currentProject()
                    engine.setMetronome(project.metronome, 0)
                    // Playback stays inside the global trim (SPEC 2.6).
                    engine.play(Trim.playStart(project, playhead))
                } else {
                    finishTake(engine.stop(), interrupted = false)
                }
            }
        }
    }

    fun stop() {
        viewModelScope.launch { ops.withLock { finishTake(engine.stop(), interrupted = false) } }
    }

    fun recordNewTrack(displayRotationDegrees: Int = 0) = record(null, displayRotationDegrees)

    /** Re-records [trackId]: a new take becomes active; the previous ones are kept (SPEC 2.2). */
    fun recordNewTake(trackId: String, displayRotationDegrees: Int = 0) = record(trackId, displayRotationDegrees)

    private fun record(existingTrackId: String?, displayRotationDegrees: Int) {
        viewModelScope.launch {
            ops.withLock {
                if (engine.status.value.transport != TransportState.IDLE) return@withLock
                // The performer needs to see the camera while recording.
                local.update { it.copy(panelMode = PanelMode.CAMERA) }
                val project = currentProject()
                val existing = existingTrackId?.let { id -> project.tracks.firstOrNull { it.id == id } ?: return@withLock }
                val trackId = existing?.id ?: UUID.randomUUID().toString()
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
                // Persist the take before recording, so a crash can recover it (SPEC 2.1).
                val updated = repository.update(project.id) { p ->
                    if (existing == null) {
                        val name = "${TRACK_NAME_PREFIX}${p.tracks.size + 1}"
                        p.copy(tracks = p.tracks + Track(trackId, name, listOf(take), takeId))
                    } else {
                        p.copy(tracks = p.tracks.map {
                            if (it.id == trackId) it.copy(takes = it.takes + take, activeTakeId = takeId) else it
                        })
                    }
                }
                recordingNewTrack = existing == null
                previousActiveTakeId = existing?.activeTake?.id
                // A re-take replaces what the track had: the old take is not played meanwhile
                // (the new, still empty take is the active one).
                if (existing != null) loadTracks(updated)
                val route = local.value.route
                val calibration = route?.let { profiles.get(it.key)?.calibration }
                recordingCalibration = if (route != null && calibration != null) route.key to calibration else null
                // Count-in: the transport starts before project frame 0, so the take begins
                // with the count-in clicks in the headphones (never in the recording).
                val countIn = project.metronome.countInFrames(project.sampleRate)
                engine.setMetronome(project.metronome, countIn)
                // Video first: its first frame is placed on the timeline by timestamp, so it
                // may start before or after the audio; starting it first just avoids a gap.
                val wantVideo = local.value.videoEnabled && camera.state.value.isOpen
                videoRecording = wantVideo &&
                    camera.startRecording(repository.takeAudioFile(project.id, trackId, "$takeId.mp4"), displayRotationDegrees)
                if (wantVideo && !videoRecording) Log.w(LOG_TAG, "Video did not start; recording audio only")
                if (engine.record(file, -countIn, mode)) {
                    recordingTrackId = trackId
                    recordingTakeId = takeId
                    local.update { it.copy(recordingTrackId = trackId) }
                    Log.i(LOG_TAG, if (existing == null) "Recording new track" else "Recording take ${existing.takes.size + 1} of track $trackId")
                } else {
                    Log.e(LOG_TAG, "Could not start recording")
                    if (videoRecording) {
                        videoRecording = false
                        camera.stopRecording()
                    }
                    dropRecordedTake(trackId, takeId)
                    local.update { it.copy(notice = RecorderNotice.RECORD_FAILED) }
                }
            }
        }
    }

    /** Makes [takeId] the take that plays and exports for its track. Only while idle. */
    fun setActiveTake(trackId: String, takeId: String) {
        viewModelScope.launch {
            ops.withLock {
                if (engine.status.value.transport != TransportState.IDLE) return@withLock
                val updated = repository.update(projectId) { p ->
                    p.copy(tracks = p.tracks.map { t ->
                        if (t.id == trackId && t.takes.any { it.id == takeId }) t.copy(activeTakeId = takeId) else t
                    })
                }
                if (engine.status.value.isOpen) loadTracks(updated)
            }
        }
    }

    /** Deletes one take (files included). The last take of a track is removed with the track. */
    fun deleteTake(trackId: String, takeId: String) {
        viewModelScope.launch {
            ops.withLock {
                if (engine.status.value.transport != TransportState.IDLE) return@withLock
                val track = currentProject().tracks.firstOrNull { it.id == trackId } ?: return@withLock
                if (track.takes.size <= 1) return@withLock
                removeTake(trackId, takeId, restoreActive = null)
                if (engine.status.value.isOpen) loadTracks(currentProject())
            }
        }
    }

    // --- mixer (SPEC 2.5): every change is heard at once; releasing a control saves it ---

    fun toggleMute(trackId: String) = saveMix { p -> p.updateTrack(trackId) { it.copy(muted = !it.muted) } }

    fun toggleSolo(trackId: String) = saveMix { p -> p.updateTrack(trackId) { it.copy(solo = !it.solo) } }

    /** Live fader move: changes the engine gains only (no disk write). */
    fun previewTrackVolume(trackId: String, db: Double) =
        previewMix { p -> p.updateTrack(trackId) { it.copy(volumeDb = db) } }

    /** Fader released: persists the volume. */
    fun setTrackVolume(trackId: String, db: Double) =
        saveMix { p -> p.updateTrack(trackId) { it.copy(volumeDb = db.coerceIn(Levels.MIN_DB, Levels.MAX_DB)) } }

    fun previewTrackPan(trackId: String, pan: Float) =
        previewMix { p -> p.updateTrack(trackId) { it.copy(pan = pan) } }

    fun setTrackPan(trackId: String, pan: Float) =
        saveMix { p -> p.updateTrack(trackId) { it.copy(pan = pan.coerceIn(-1f, 1f)) } }

    fun previewMasterVolume(db: Double) = previewMix { p -> p.copy(mixer = p.mixer.copy(masterVolumeDb = db)) }

    fun setMasterVolume(db: Double) = saveMix { p ->
        p.copy(mixer = p.mixer.copy(masterVolumeDb = db.coerceIn(Mixing.MIN_MASTER_DB, Mixing.MAX_MASTER_DB)))
    }

    fun setLimiter(on: Boolean) = saveMix { p -> p.copy(mixer = p.mixer.copy(limiter = on)) }

    /** Resets every track's volume, pan, mute, solo and phase, and the master (limiter back on). */
    fun resetMix() = saveMix { p ->
        p.copy(
            // EQ, sends and the reverb are kept: they have their own reset/presets.
            mixer = MixerSettings(reverb = p.mixer.reverb),
            tracks = p.tracks.map { it.copy(volumeDb = 0.0, pan = 0f, muted = false, solo = false, phaseInverted = false) },
        )
    }

    fun togglePhase(trackId: String) = saveMix { p -> p.updateTrack(trackId) { it.copy(phaseInverted = !it.phaseInverted) } }

    fun previewTrackEq(trackId: String, eq: TrackEq) = previewMix { p -> p.updateTrack(trackId) { it.copy(eq = eq) } }

    fun setTrackEq(trackId: String, eq: TrackEq) = saveMix { p -> p.updateTrack(trackId) { it.copy(eq = eq) } }

    fun previewTrackSend(trackId: String, db: Double) =
        previewMix { p -> p.updateTrack(trackId) { it.copy(reverbSendDb = db) } }

    fun setTrackSend(trackId: String, db: Double) =
        saveMix { p -> p.updateTrack(trackId) { it.copy(reverbSendDb = db.coerceIn(Levels.MIN_DB, Mixing.MAX_SEND_DB)) } }

    fun previewReverb(reverb: ReverbSettings) = previewMix { p -> p.copy(mixer = p.mixer.copy(reverb = reverb)) }

    fun setReverb(reverb: ReverbSettings) = saveMix { p -> p.copy(mixer = p.mixer.copy(reverb = reverb)) }

    private fun Project.updateTrack(trackId: String, transform: (Track) -> Track): Project =
        copy(tracks = tracks.map { if (it.id == trackId) transform(it) else it })

    private fun previewMix(transform: (Project) -> Project) {
        val project = uiState.value.project ?: return
        viewModelScope.launch { applyMix(transform(project)) }
    }

    private fun saveMix(transform: (Project) -> Project) {
        viewModelScope.launch { applyMix(repository.update(projectId, transform)) }
    }

    /**
     * Pushes every track's gains (solo affects all of them), EQ and send, the reverb and the
     * master to the engine. The engine smooths each value, so repeating unchanged ones is free.
     */
    private suspend fun applyMix(project: Project) {
        val anySolo = Mixing.anySolo(project)
        val byId = project.tracks.associateBy { it.id }
        engineTrackIds.forEachIndexed { index, id ->
            val track = byId[id] ?: return@forEachIndexed
            engine.setTrackGain(index, Mixing.trackGain(track, anySolo))
            engine.setTrackEq(index, track.eq)
            engine.setTrackSend(index, Mixing.sendGain(track))
        }
        engine.setReverb(project.mixer.reverb)
        engine.setMaster(Mixing.masterGain(project.mixer), project.mixer.limiter)
    }

    /** Manual nudge on top of the automatic alignment (SPEC 2.2). Only while idle. */
    fun setManualOffsetMs(trackId: String, ms: Double) {
        val sampleRate = uiState.value.project?.sampleRate ?: return
        setManualOffsetFrames(trackId, AlignmentMath.msToFrames(ms, sampleRate))
    }

    /** Sample-accurate manual offset of the active take (waveform drag, SPEC 2.5). Only while idle. */
    fun setManualOffsetFrames(trackId: String, frames: Long) {
        viewModelScope.launch {
            ops.withLock {
                if (engine.status.value.transport != TransportState.IDLE) return@withLock
                val project = currentProject()
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
        val profile = local.value.route?.let { profiles.get(it.key) }
        engineTrackIds = emptyList()
        val outcome = routeCalibrator.measure(task, profile, File(context.cacheDir, "calibration.wav")) { progress ->
            local.update { s ->
                val running = s.calibration as? CalibrationUi.Running ?: return@update s
                s.copy(calibration = running.copy(progress = progress))
            }
        }
        if (outcome is CalibrationOutcome.Leak) saveProfile { it.copy(leak = outcome.result) }
        return outcome
    }

    fun deleteProfileCalibration() {
        viewModelScope.launch { saveProfile { it.copy(calibration = null) } }
    }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    // --- internals (call with `ops` held) -------------------------------------------------

    private suspend fun currentProject(): Project = repository.observe(projectId).filterNotNull().first()

    private fun newProfile(): DeviceProfile? = local.value.route?.newProfile()

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

    private fun resolveRoute(status: EngineStatus, preferredInput: AudioDevice?): AudioRoute =
        AudioRoute.of(deviceMonitor.devices.value, status, preferredInput)

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
        // Same mapping as the export render, so what plays is what exports.
        val playable = PlaybackTracks.of(project) { trackId, name -> repository.takeAudioFile(project.id, trackId, name) }
        engineTrackIds = playable.map { it.first }
        engine.setTracks(playable.map { it.second })
        engine.setReverb(project.mixer.reverb)
        engine.setMaster(Mixing.masterGain(project.mixer), project.mixer.limiter)
        local.update { it.copy(freeBytes = repository.freeBytes(), engineTrackIds = engineTrackIds) }
    }

    /** Saves the take that was being recorded (if any) with its automatic alignment. */
    private suspend fun finishTake(result: RecordingResult?, interrupted: Boolean) {
        val video = if (videoRecording) {
            videoRecording = false
            camera.stopRecording()
        } else null
        val trackId = recordingTrackId ?: run { video?.file?.delete(); return }
        val takeId = recordingTakeId ?: return
        recordingTrackId = null
        recordingTakeId = null
        local.update { it.copy(recordingTrackId = null) }
        // Nothing past the count-in (stopped before project frame 0): drop the take.
        if (result == null || result.frames <= 0 || result.startPosition + result.frames <= 0) {
            dropRecordedTake(trackId, takeId)
            if (engine.status.value.isOpen) loadTracks(currentProject())
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
        val takeVideo = video?.let { toTakeVideo(it, result, project.sampleRate) }
        takeVideo?.startPositionFrames?.let { start ->
            val audioStart = result.startPosition + alignment.autoOffsetFrames
            Log.i(
                LOG_TAG,
                "Video placed at ${"%.1f".format(AlignmentMath.framesToMs(start, project.sampleRate))} ms, " +
                    "audio take at ${"%.1f".format(AlignmentMath.framesToMs(audioStart.toDouble(), project.sampleRate))} ms " +
                    "(clock ${takeVideo.clock}${if (result.outputClockEstimated) ", output clock estimated" else ""})",
            )
        }
        val updated = repository.update(project.id) { p ->
            p.copy(tracks = p.tracks.map { track ->
                if (track.id != trackId) track else track.copy(takes = track.takes.map { take ->
                    if (take.id != takeId) take else take.copy(
                        channelCount = result.channels,
                        lengthFrames = result.frames,
                        recordStartFrame = result.startPosition,
                        autoOffsetFrames = alignment.autoOffsetFrames,
                        alignment = alignment.info,
                        video = takeVideo,
                    )
                })
            })
        }
        if (result.xrunsDuring > 0 || result.droppedFrames > 0) {
            local.update { it.copy(notice = RecorderNotice.TAKE_GLITCHED) }
        }
        if (engine.status.value.isOpen) loadTracks(updated)
    }

    /** Places the recorded video on the project timeline using the audio output clock. */
    private fun toTakeVideo(v: VideoRecording, result: RecordingResult, sampleRate: Int): TakeVideo {
        val clock = result.outputClockFrames
        val first = v.firstFrameMonotonicNanos
        val start = if (first != null && clock != null) {
            AlignmentMath.projectPositionAt(first, clock, result.outputStartFrame, result.startPosition, sampleRate)
        } else null
        if (start == null) Log.w(LOG_TAG, "Video not aligned: camera clock ${v.clock}, output clock ${clock != null}")
        return TakeVideo(
            file = v.file.name,
            lens = v.lens,
            width = v.width,
            height = v.height,
            rotationDegrees = v.rotationDegrees,
            previewMirrored = v.lens == CameraLens.FRONT,
            frameRate = v.frameRate,
            frameCount = v.frameCount,
            durationUs = v.durationUs,
            startPositionFrames = start,
            clock = v.clock,
        )
    }

    private suspend fun updateActiveTake(trackId: String, transform: (Take) -> Take): Project =
        repository.update(projectId) { p ->
            p.copy(tracks = p.tracks.map { track ->
                if (track.id != trackId) track else track.copy(takes = track.takes.map { take ->
                    if (take.id == track.activeTakeId) transform(take) else take
                })
            })
        }

    /** Drops a take that recorded nothing: the whole track if the take created it. */
    private suspend fun dropRecordedTake(trackId: String, takeId: String) {
        if (recordingNewTrack) removeTrack(trackId) else removeTake(trackId, takeId, previousActiveTakeId)
        previousActiveTakeId = null
    }

    /**
     * Removes one take and its files. The active take becomes [restoreActive] if given, else
     * stays (if another take was active) or falls back to the newest remaining take.
     */
    private suspend fun removeTake(trackId: String, takeId: String, restoreActive: String?) {
        val project = currentProject()
        val take = project.tracks.firstOrNull { it.id == trackId }?.takes?.firstOrNull { it.id == takeId } ?: return
        repository.takeAudioFile(project.id, trackId, take.audioFile).delete()
        repository.takeAudioFile(project.id, trackId, take.audioFile + Waveforms.CACHE_SUFFIX).delete()
        repository.takeAudioFile(project.id, trackId, take.video?.file ?: "${take.id}.mp4").delete()
        deleteProxy(project.id, trackId, take.id)
        repository.update(project.id) { p ->
            p.copy(tracks = p.tracks.map { t ->
                if (t.id != trackId) return@map t
                val remaining = t.takes.filterNot { it.id == takeId }
                val active = restoreActive?.takeIf { id -> remaining.any { it.id == id } }
                    ?: t.activeTakeId?.takeIf { id -> id != takeId && remaining.any { it.id == id } }
                    ?: remaining.lastOrNull()?.id
                t.copy(takes = remaining, activeTakeId = active)
            })
        }
        Log.i(LOG_TAG, "Take $takeId removed from track $trackId")
    }

    /** The proxy (SPEC 3.6) and a half-made one, if any. */
    private fun deleteProxy(projectId: String, trackId: String, takeId: String) {
        val proxy = repository.takeAudioFile(projectId, trackId, Proxies.fileName(takeId))
        proxy.delete()
        File(proxy.path + ".tmp").delete()
    }

    private suspend fun removeTrack(trackId: String) {
        val project = currentProject()
        project.tracks.firstOrNull { it.id == trackId }?.takes?.forEach { take ->
            repository.takeAudioFile(project.id, trackId, take.audioFile).delete()
            repository.takeAudioFile(project.id, trackId, take.audioFile + Waveforms.CACHE_SUFFIX).delete()
            // The video may exist before the take knows about it (recording in progress).
            repository.takeAudioFile(project.id, trackId, take.video?.file ?: "${take.id}.mp4").delete()
            deleteProxy(project.id, trackId, take.id)
        }
        // The track folder, now empty (File.delete leaves it alone if anything is left).
        repository.takeAudioFile(project.id, trackId, "x").parentFile?.delete()
        repository.update(project.id) { p -> p.copy(tracks = p.tracks.filterNot { it.id == trackId }) }
    }

    companion object {
        const val ARG_PROJECT_ID = "projectId"
        private const val DEVICE_DEBOUNCE_MS = 400L
        private const val TAP_RESET_MS = 2_000L
        private const val STATUS_WAIT_MS = 1_000L
        private const val MAX_VIDEO_OFFSET_MS = 2_000.0
        /** Frames shown on each side of the clap in the sync check. */
        private const val SYNC_FRAMES = 3
        private const val SYNC_THUMB_WIDTH = 360
        private const val GHOST_WIDTH = 720
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
