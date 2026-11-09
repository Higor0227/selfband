// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.higor0227.selfband.core.audio.OfflineMix
import io.github.higor0227.selfband.core.audio.PlaybackTracks
import io.github.higor0227.selfband.core.export.internal.MediaStoreFiles
import io.github.higor0227.selfband.core.export.internal.Mp4Export
import io.github.higor0227.selfband.core.export.internal.PassPlan
import io.github.higor0227.selfband.core.export.internal.VideoExportException
import io.github.higor0227.selfband.core.export.internal.VideoPassPlanner
import io.github.higor0227.selfband.core.export.internal.VideoPasses
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.model.value
import io.github.higor0227.selfband.core.project.ProjectRepository
import io.github.higor0227.selfband.core.video.CanvasRendererFactory
import io.github.higor0227.selfband.core.video.DecoderStartException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Runs exports one at a time (SPEC 2.7). The work lives here, in the app process; the
 * [ExportService] keeps the process in the foreground and shows the progress while
 * [state] is [ExportState.Running].
 */
@Singleton
class ExportManager @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProjectRepository,
    private val canvasFactory: CanvasRendererFactory,
    private val planner: VideoPassPlanner,
) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Starts an export; false if one is already running. Call from the foreground (UI). */
    fun start(projectId: String, options: ExportOptions): Boolean {
        if (_state.value is ExportState.Running || options.isEmpty) return false
        _state.value = ExportState.Running(projectId, "", firstStep(options))
        ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
        job = scope.launch { run(projectId, options) }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /** Forgets a finished, failed or cancelled export. */
    fun dismiss() {
        if (_state.value !is ExportState.Running) _state.value = ExportState.Idle
    }

    private suspend fun run(projectId: String, options: ExportOptions) {
        val store = MediaStoreFiles(context.contentResolver)
        val written = mutableListOf<Written>()
        try {
            val project = repository.observe(projectId).first()
                ?: return fail(projectId, ExportFailure.EMPTY_PROJECT)
            _state.value = ExportState.Running(projectId, project.name, firstStep(options))
            if (render(project, options, store, written) == null) {
                store.discard(written.map { it.item })
                return
            }
            val published = store.publish(written.map { it.item })
            Log.i(LOG_TAG, "Export of ${project.name}: ${published.joinToString { it.displayName }}")
            _state.value = ExportState.Done(
                projectId,
                project.name,
                written.map { it.folder }.distinct(),
                published.map { ExportOutput(it.uri.toString(), it.displayName, it.mimeType) },
            )
        } catch (e: CancellationException) {
            store.discard(written.map { it.item })
            _state.value = ExportState.Cancelled(projectId)
            Log.i(LOG_TAG, "Export cancelled")
            throw e
        } catch (e: VideoExportException) {
            store.discard(written.map { it.item })
            Log.e(LOG_TAG, "Video export failed", e)
            fail(projectId, ExportFailure.VIDEO_FAILED, e.message)
        } catch (e: IOException) {
            store.discard(written.map { it.item })
            Log.e(LOG_TAG, "Export failed", e)
            fail(projectId, ExportFailure.WRITE_FAILED, e.message)
        } catch (e: RuntimeException) {
            store.discard(written.map { it.item })
            Log.e(LOG_TAG, "Export failed", e)
            fail(projectId, ExportFailure.WRITE_FAILED, e.message)
        }
    }

    private fun firstStep(options: ExportOptions) = when {
        options.video -> ExportStep.VIDEO
        options.mixWav -> ExportStep.MIX
        else -> ExportStep.STEMS
    }

    private fun fail(projectId: String, reason: ExportFailure, detail: String? = null) {
        _state.value = ExportState.Failed(projectId, reason, detail)
    }

    private class Written(val item: MediaStoreFiles.Item, val folder: String)

    /** Video, mix and/or stems over the trim range. Null when it failed (state already set). */
    private suspend fun render(
        project: Project,
        options: ExportOptions,
        store: MediaStoreFiles,
        written: MutableList<Written>,
    ): Unit? {
        val start = project.trimStart
        val length = project.trimEnd - start
        val tracks = PlaybackTracks.of(project) { trackId, name -> repository.takeAudioFile(project.id, trackId, name) }
        if (length <= 0 || tracks.isEmpty()) {
            fail(project.id, ExportFailure.EMPTY_PROJECT)
            return null
        }
        val folder = ExportNames.sanitize(project.name)
        val baseName = ExportNames.sanitize(project.name)
        var passCount = 1
        var pass = 0
        fun totalUnits() = (if (options.video) length * VIDEO_WEIGHT * passCount else 0L) +
            (if (options.mixWav) length else 0L) +
            (if (options.stems) length * tracks.size else 0L)
        var doneUnits = 0L
        var lastProgress = -1f
        var lastStep: ExportStep? = null
        var lastStem = -1
        var lastPass = -1
        fun advance(units: Long, step: ExportStep, stemIndex: Int = 0) {
            doneUnits += units
            val p = (doneUnits.toFloat() / totalUnits()).coerceIn(0f, 1f)
            // Throttled to 0.5 % steps, but a new step, stem or pass shows up at once.
            if (p - lastProgress >= 0.005f || p >= 1f || step != lastStep || stemIndex != lastStem || pass != lastPass) {
                lastProgress = p
                lastStep = step
                lastStem = stemIndex
                lastPass = pass
                val passes = if (step == ExportStep.VIDEO) passCount else 0
                _state.value = ExportState.Running(project.id, project.name, step, stemIndex, tracks.size, p, pass, passes)
            }
        }

        val mix = OfflineMix.open(project.sampleRate, tracks.map { it.second }, project.mixer)
        if (mix == null) {
            fail(project.id, ExportFailure.UNREADABLE_TAKE)
            return null
        }
        mix.use {
            if (options.video) {
                val spec = VideoSpec.of(project.aspectRatio.value, options.videoOptions)
                var maxDecoders: Int? = null
                var retries = 0
                while (true) {
                    val plan = planner.plan(project, spec, maxDecoders)
                    val groups = plan.groups
                        ?: throw VideoExportException("only ${plan.maxDecoders} video decoder(s) available")
                    passCount = groups.size
                    pass = if (groups.size > 1) 1 else 0
                    doneUnits = 0
                    advance(0, ExportStep.VIDEO)
                    if (groups.size > 1) {
                        Log.i(LOG_TAG, "export: ${plan.scene.decodedLayers.size} videos in ${groups.size} passes of up to ${plan.maxDecoders} decoders")
                    }
                    try {
                        val item = renderVideo(project, spec, plan, groups, mix, store, baseName) { frames, p ->
                            pass = p
                            advance(frames * VIDEO_WEIGHT, ExportStep.VIDEO)
                        }
                        written += Written(item, MediaStoreFiles.relativePath(MediaStoreFiles.Kind.VIDEO, null))
                        break
                    } catch (e: DecoderStartException) {
                        // More decoders declared than the device can run: fewer per pass.
                        val used = minOf(plan.maxDecoders, plan.scene.decodedLayers.size)
                        val next = minOf(e.opened, used - 1)
                        if (next < 2 || ++retries > MAX_DECODER_RETRIES) {
                            throw VideoExportException("video decoders could not start", e)
                        }
                        Log.w(LOG_TAG, "export: decoder ${e.opened + 1} did not start; retrying with $next per pass", e)
                        maxDecoders = next
                    }
                }
            }
            val audioFolder = MediaStoreFiles.relativePath(MediaStoreFiles.Kind.AUDIO, folder)
            val buffer = FloatArray(OfflineMix.MAX_BLOCK_FRAMES * 2)
            if (options.mixWav) {
                advance(0, ExportStep.MIX)
                mix.reset()
                val name = context.getString(R.string.export_mix_file, baseName) + ".wav"
                val item = store.create(MediaStoreFiles.Kind.AUDIO, folder, name, MediaStoreFiles.MIME_WAV) { pfd ->
                    FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                        WavFileWriter(channel, 2, project.sampleRate, options.wavFormat).use { wav ->
                            renderRange(start, length) { position, frames ->
                                mix.renderMix(position, frames, buffer)
                                wav.write(buffer, frames * 2)
                                advance(frames.toLong(), ExportStep.MIX)
                            }
                        }
                    }
                }
                written += Written(item, audioFolder)
            }
            if (options.stems) {
                tracks.forEachIndexed { i, (trackId, _) ->
                    val number = project.tracks.indexOfFirst { it.id == trackId } + 1
                    val channels = mix.trackChannels(i)
                    val name = context.getString(R.string.export_stem_file, baseName, number) + ".wav"
                    advance(0, ExportStep.STEMS, i + 1)
                    val item = store.create(MediaStoreFiles.Kind.AUDIO, folder, name, MediaStoreFiles.MIME_WAV) { pfd ->
                        FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                            WavFileWriter(channel, channels, project.sampleRate, options.wavFormat).use { wav ->
                                renderRange(start, length) { position, frames ->
                                    mix.renderStem(i, position, frames, buffer)
                                    wav.write(buffer, frames * channels)
                                    advance(frames.toLong(), ExportStep.STEMS, i + 1)
                                }
                            }
                        }
                    }
                    written += Written(item, audioFolder)
                }
            }
        }
        return Unit
    }

    /**
     * The MP4 in [VideoPasses]: intermediates (video only, in the cache) for all groups but
     * the last, then the final file with the mix. [onProgress] gets audio-frame units and the
     * 1-based pass (0 for a single pass).
     */
    private suspend fun renderVideo(
        project: Project,
        spec: VideoSpec,
        plan: PassPlan,
        groups: List<IntRange>,
        mix: OfflineMix,
        store: MediaStoreFiles,
        baseName: String,
        onProgress: (frames: Long, pass: Int) -> Unit,
    ): MediaStoreFiles.Item {
        val start = project.trimStart
        val length = project.trimEnd - start
        val dir = File(context.cacheDir, PASS_DIR).apply { deleteRecursively() }
        try {
            var previous: File? = null
            for ((i, group) in groups.withIndex()) {
                val passNumber = if (groups.size > 1) i + 1 else 0
                val scene = VideoPasses.passScene(plan.scene, group, previous, spec.width, spec.height, spec.frameRate, start)
                fun encode(fd: FileDescriptor, passSpec: VideoSpec, passMix: OfflineMix?) =
                    Mp4Export(fd, passSpec, project.sampleRate, start, length, passMix) { surface ->
                        canvasFactory.create(scene, surface, passSpec.width, passSpec.height)
                    }
                if (i < groups.lastIndex) {
                    dir.mkdirs()
                    val out = File(dir, "pass${i + 1}.mp4")
                    val high = spec.copy(bitRate = (spec.bitRate * VideoPasses.INTERMEDIATE_BITRATE_FACTOR).toInt())
                    RandomAccessFile(out, "rw").use { file ->
                        onVideoThread { encode(file.fd, high, null).run { onProgress(it, passNumber) } }
                    }
                    previous?.delete()
                    previous = out
                } else {
                    return store.create(MediaStoreFiles.Kind.VIDEO, null, "$baseName.mp4", MediaStoreFiles.MIME_MP4) { pfd ->
                        mix.reset()
                        onVideoThread { encode(pfd.fileDescriptor, spec, mix).run { onProgress(it, passNumber) } }
                    }
                }
            }
            error("no pass")
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The renderer's EGL context and the codecs stay on one dedicated thread. */
    private suspend fun onVideoThread(block: suspend () -> Unit) {
        val thread = Executors.newSingleThreadExecutor { Thread(it, "SelfbandExportVideo") }
        try {
            withContext(thread.asCoroutineDispatcher()) { block() }
        } finally {
            thread.shutdown()
        }
    }

    /** Calls [block] for consecutive blocks covering [start, start + length), checking for cancellation. */
    private suspend fun renderRange(start: Long, length: Long, block: (position: Long, frames: Int) -> Unit) {
        var done = 0L
        while (done < length) {
            coroutineContext.ensureActive()
            val frames = minOf(OfflineMix.MAX_BLOCK_FRAMES.toLong(), length - done).toInt()
            block(start + done, frames)
            done += frames
        }
    }

    private companion object {
        /** Rendering a video frame costs about this many audio frames' worth of WAV writing. */
        const val VIDEO_WEIGHT = 4L
        /** Cache folder of the multi-pass intermediates, emptied before and after each export. */
        const val PASS_DIR = "export-passes"
        const val MAX_DECODER_RETRIES = 4
    }
}
