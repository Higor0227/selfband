// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.export

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.higor0227.selfband.core.export.DecoderLimit
import io.github.higor0227.selfband.core.export.ExportManager
import io.github.higor0227.selfband.core.export.ExportOptions
import io.github.higor0227.selfband.core.export.ExportState
import io.github.higor0227.selfband.core.export.VideoCodec
import io.github.higor0227.selfband.core.export.VideoEncoders
import io.github.higor0227.selfband.core.export.VideoOptions
import io.github.higor0227.selfband.core.export.VideoResolution
import io.github.higor0227.selfband.core.export.VideoSpec
import io.github.higor0227.selfband.core.export.WavFormat
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.model.value
import io.github.higor0227.selfband.core.project.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

/** Codec, size and frame rate: what decides whether the device can encode it. */
data class VideoMode(val codec: VideoCodec, val resolution: VideoResolution, val frameRate: Int)

val VideoOptions.mode: VideoMode get() = VideoMode(codec, resolution, frameRate)

data class ExportUiState(
    val project: Project? = null,
    val options: ExportOptions = ExportOptions(),
    /** The export of this project (running or finished), or null. */
    val export: ExportState? = null,
    /** True while another project is being exported. */
    val busyElsewhere: Boolean = false,
    /** Modes this device can encode for the project's canvas, with their bitrate range (null until known). */
    val modes: Map<VideoMode, IntRange?>? = null,
    /** Set when the project has more videos than simultaneous decoders. */
    val decoderLimit: DecoderLimit? = null,
) {
    val videoSpec: VideoSpec
        get() = VideoSpec.of(project?.aspectRatio?.value ?: (9f / 16f), options.videoOptions)

    /** Video is selected but cannot be made on this device as configured. */
    val videoBlocked: Boolean
        get() = options.video && (decoderLimit != null || modes?.containsKey(options.videoOptions.mode) == false)

    val canStart: Boolean
        get() = project != null && project.trimEnd > project.trimStart && !options.isEmpty &&
            export !is ExportState.Running && !busyElsewhere && !videoBlocked
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: ProjectRepository,
    private val manager: ExportManager,
    private val encoders: VideoEncoders,
) : ViewModel() {

    private val projectId: String = checkNotNull(savedStateHandle[ARG_PROJECT_ID])
    private val options = MutableStateFlow(ExportOptions())
    private val modes = MutableStateFlow<Map<VideoMode, IntRange?>?>(null)
    private val decoderLimit = MutableStateFlow<DecoderLimit?>(null)
    private val project = repository.observe(projectId)

    val uiState: StateFlow<ExportUiState> =
        combine(project, options, manager.state, modes, decoderLimit) { project, opts, export, modes, limit ->
            val mine = export.takeIf { it.projectId() == projectId }
            ExportUiState(
                project = project,
                options = opts,
                export = mine,
                busyElsewhere = export is ExportState.Running && mine == null,
                modes = modes,
                decoderLimit = limit,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportUiState())

    init {
        // What the encoders take depends only on the canvas shape (SPEC 2.7: "4K when supported").
        viewModelScope.launch {
            project.filterNotNull().map { it.aspectRatio.value }.distinctUntilChanged()
                .mapLatest { aspect -> encoderModes(aspect) }
                .flowOn(Dispatchers.Default)
                .collect { found ->
                    modes.value = found
                    options.update { it.copy(videoOptions = fit(it.videoOptions, found)) }
                }
        }
        // Simultaneous decoders (SPEC 3.5); re-checked when the tracks or canvas change.
        viewModelScope.launch {
            project.filterNotNull().map { it.tracks to it.canvas }.distinctUntilChanged()
                .mapLatest { encoders.decoderLimit(projectId) }
                .flowOn(Dispatchers.IO)
                .collect { decoderLimit.value = it }
        }
    }

    private fun encoderModes(aspect: Float): Map<VideoMode, IntRange?> {
        val found = LinkedHashMap<VideoMode, IntRange?>()
        for (codec in VideoCodec.entries) for (resolution in VideoResolution.entries) for (fps in VideoOptions.FRAME_RATES) {
            val spec = VideoSpec.forAspect(aspect, resolution.shortSide, fps, codec)
            if (encoders.supports(codec, spec.width, spec.height, fps)) {
                found[VideoMode(codec, resolution, fps)] = encoders.bitRateRange(codec, spec.width, spec.height, fps)
            }
        }
        return found
    }

    fun setVideo(on: Boolean) = options.update { it.copy(video = on) }
    fun setMixWav(on: Boolean) = options.update { it.copy(mixWav = on) }
    fun setStems(on: Boolean) = options.update { it.copy(stems = on) }
    fun setFormat(format: WavFormat) = options.update { it.copy(wavFormat = format) }

    // The value just chosen is kept; the other choices adapt if the combination is not encodable.
    fun setCodec(codec: VideoCodec) = updateVideo({ it.codec == codec }) { it.copy(codec = codec) }
    fun setResolution(resolution: VideoResolution) =
        updateVideo({ it.resolution == resolution }) { it.copy(resolution = resolution) }
    fun setFrameRate(frameRate: Int) = updateVideo({ it.frameRate == frameRate }) { it.copy(frameRate = frameRate) }

    /** Null = automatic. */
    fun setBitRate(bitRate: Int?) = updateVideo({ true }) { it.copy(bitRate = bitRate) }

    private fun updateVideo(keep: (VideoMode) -> Boolean, change: (VideoOptions) -> VideoOptions) {
        options.update { it.copy(videoOptions = fit(change(it.videoOptions), modes.value, keep)) }
    }

    fun start() {
        if (uiState.value.canStart) manager.start(projectId, options.value)
    }

    fun cancel() = manager.cancel()

    /** Back to the options after a finished export. */
    fun reset() = manager.dismiss()

    companion object {
        const val ARG_PROJECT_ID = "projectId"

        /**
         * The supported mode nearest [wanted], among those matching [keep] when any does: same
         * codec first, then same frame rate, then the closest size (smaller preferred). A manual
         * bitrate is kept inside the encoder's range.
         */
        internal fun fit(
            wanted: VideoOptions,
            modes: Map<VideoMode, IntRange?>?,
            keep: (VideoMode) -> Boolean = { true },
        ): VideoOptions {
            if (modes.isNullOrEmpty()) return wanted
            val target = wanted.mode
            val candidates = modes.keys.filter(keep).ifEmpty { modes.keys.toList() }
            val mode = if (target in modes) target else candidates.minBy { m ->
                val sizeSteps = m.resolution.ordinal - target.resolution.ordinal
                (if (m.codec != target.codec) 100.0 else 0.0) +
                    (if (m.frameRate != target.frameRate) 10.0 else 0.0) +
                    abs(sizeSteps) + (if (sizeSteps > 0) 0.5 else 0.0)
            }
            val range = modes[mode]
            val bitRate = wanted.bitRate?.let { b -> range?.let { b.coerceIn(it.first, it.last) } ?: b }
            return VideoOptions(mode.codec, mode.resolution, mode.frameRate, bitRate)
        }
    }
}

private fun ExportState.projectId(): String? = when (this) {
    ExportState.Idle -> null
    is ExportState.Running -> projectId
    is ExportState.Done -> projectId
    is ExportState.Failed -> projectId
    is ExportState.Cancelled -> projectId
}
