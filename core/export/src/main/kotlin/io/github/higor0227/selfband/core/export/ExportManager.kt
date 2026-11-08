// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.higor0227.selfband.core.audio.OfflineMix
import io.github.higor0227.selfband.core.audio.PlaybackTracks
import io.github.higor0227.selfband.core.export.internal.MediaStoreAudio
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.project.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Runs exports one at a time (SPEC 2.7). The work lives here, in the app process; the
 * [ExportService] keeps the process in the foreground and shows the progress while
 * [state] is [ExportState.Running].
 */
@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProjectRepository,
) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Starts an export; false if one is already running. Call from the foreground (UI). */
    fun start(projectId: String, options: ExportOptions): Boolean {
        if (_state.value is ExportState.Running || options.isEmpty) return false
        _state.value = ExportState.Running(projectId, "", ExportStep.MIX)
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
        val store = MediaStoreAudio(context.contentResolver)
        val written = mutableListOf<MediaStoreAudio.Item>()
        try {
            val project = repository.observe(projectId).first()
                ?: return fail(projectId, ExportFailure.EMPTY_PROJECT)
            _state.value = ExportState.Running(projectId, project.name, ExportStep.MIX)
            val outputs = renderAudio(project, options, store, written)
                ?: return
            store.publish(written)
            Log.i(LOG_TAG, "Export of ${project.name}: ${outputs.size} file(s)")
            _state.value = ExportState.Done(
                projectId, project.name, MediaStoreAudio.relativePath(ExportNames.sanitize(project.name)), outputs,
            )
        } catch (e: CancellationException) {
            store.discard(written)
            _state.value = ExportState.Cancelled(projectId)
            Log.i(LOG_TAG, "Export cancelled")
            throw e
        } catch (e: IOException) {
            store.discard(written)
            Log.e(LOG_TAG, "Export failed", e)
            fail(projectId, ExportFailure.WRITE_FAILED, e.message)
        } catch (e: RuntimeException) {
            store.discard(written)
            Log.e(LOG_TAG, "Export failed", e)
            fail(projectId, ExportFailure.WRITE_FAILED, e.message)
        }
    }

    private fun fail(projectId: String, reason: ExportFailure, detail: String? = null) {
        _state.value = ExportState.Failed(projectId, reason, detail)
    }

    /** Mix and/or stems over the trim range. Null when it failed (state already set). */
    private suspend fun renderAudio(
        project: Project,
        options: ExportOptions,
        store: MediaStoreAudio,
        written: MutableList<MediaStoreAudio.Item>,
    ): List<ExportOutput>? {
        val start = project.trimStart
        val length = project.trimEnd - start
        val tracks = PlaybackTracks.of(project) { trackId, name -> repository.takeAudioFile(project.id, trackId, name) }
        if (length <= 0 || tracks.isEmpty()) {
            fail(project.id, ExportFailure.EMPTY_PROJECT)
            return null
        }
        val folder = ExportNames.sanitize(project.name)
        val baseName = ExportNames.sanitize(project.name)
        val totalUnits = (if (options.mixWav) length else 0L) + (if (options.stems) length * tracks.size else 0L)
        var doneUnits = 0L
        var lastProgress = -1f
        var lastStem = -1
        fun advance(frames: Int, step: ExportStep, stemIndex: Int = 0) {
            doneUnits += frames
            val p = doneUnits.toFloat() / totalUnits
            // Throttled to 0.5 % steps, but a new stem shows up at once.
            if (p - lastProgress >= 0.005f || p >= 1f || stemIndex != lastStem) {
                lastProgress = p
                lastStem = stemIndex
                _state.value = ExportState.Running(project.id, project.name, step, stemIndex, tracks.size, p)
            }
        }

        val mix = OfflineMix.open(project.sampleRate, tracks.map { it.second }, project.mixer)
        if (mix == null) {
            fail(project.id, ExportFailure.UNREADABLE_TAKE)
            return null
        }
        mix.use {
            val buffer = FloatArray(OfflineMix.MAX_BLOCK_FRAMES * 2)
            if (options.mixWav) {
                val name = context.getString(R.string.export_mix_file, baseName) + ".wav"
                written += store.create(folder, name) { channel ->
                    WavFileWriter(channel, 2, project.sampleRate, options.wavFormat).use { wav ->
                        renderRange(start, length) { position, frames ->
                            mix.renderMix(position, frames, buffer)
                            wav.write(buffer, frames * 2)
                            advance(frames, ExportStep.MIX)
                        }
                    }
                }
            }
            if (options.stems) {
                tracks.forEachIndexed { i, (trackId, _) ->
                    val number = project.tracks.indexOfFirst { it.id == trackId } + 1
                    val channels = mix.trackChannels(i)
                    val name = context.getString(R.string.export_stem_file, baseName, number) + ".wav"
                    advance(0, ExportStep.STEMS, i + 1)
                    written += store.create(folder, name) { channel ->
                        WavFileWriter(channel, channels, project.sampleRate, options.wavFormat).use { wav ->
                            renderRange(start, length) { position, frames ->
                                mix.renderStem(i, position, frames, buffer)
                                wav.write(buffer, frames * channels)
                                advance(frames, ExportStep.STEMS, i + 1)
                            }
                        }
                    }
                }
            }
        }
        return written.map { ExportOutput(it.uri.toString(), it.displayName, MediaStoreAudio.MIME_WAV) }
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
}
