// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video.internal

import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.model.Proxies
import io.github.higor0227.selfband.core.model.SelfbandLog
import io.github.higor0227.selfband.core.video.ProxyGenerator
import io.github.higor0227.selfband.core.video.ProxyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** [ProxyGenerator] with [ProxyTranscoder], one proxy at a time on a low-priority thread. */
@Singleton
internal class MediaCodecProxyGenerator @Inject constructor() : ProxyGenerator {
    private class Job(val source: File, val target: File)

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "SelfbandProxies").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Keyed by target path; guarded by itself. */
    private val queue = LinkedHashMap<String, Job>()
    /** Targets that failed once: not retried until the app restarts. */
    private val failed = HashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val paused = MutableStateFlow(false)
    @Volatile private var stopCurrent = false
    private val mutableState = MutableStateFlow(ProxyState())
    override val state: StateFlow<ProxyState> = mutableState.asStateFlow()

    init {
        scope.launch { work() }
    }

    override fun request(project: Project, file: (trackId: String, fileName: String) -> File) {
        val wanted = project.tracks.mapNotNull { track ->
            val take = track.activeTake ?: return@mapNotNull null
            val video = take.video ?: return@mapNotNull null
            Job(file(track.id, video.file), file(track.id, Proxies.fileName(take.id)))
        }
        if (wanted.isEmpty()) return
        io.launch {
            var added = 0
            synchronized(queue) {
                for (job in wanted) {
                    val key = job.target.absolutePath
                    if (key in queue || key in failed || job.target.exists() || !job.source.exists()) continue
                    queue[key] = job
                    added++
                }
                mutableState.update { it.copy(pending = queue.size) }
            }
            if (added > 0) {
                Log.i(LOG_TAG, "proxy: $added queued")
                wake.trySend(Unit)
            }
        }
    }

    override fun setPaused(paused: Boolean) {
        if (this.paused.value == paused) return
        this.paused.value = paused
        if (paused) stopCurrent = true else wake.trySend(Unit)
    }

    private suspend fun work() {
        while (true) {
            wake.receive()
            while (true) {
                paused.first { !it }
                val job = synchronized(queue) { queue.values.firstOrNull() } ?: break
                make(job)
            }
        }
    }

    private fun make(job: Job) {
        val key = job.target.absolutePath
        val temp = File(job.target.path + ".tmp")
        stopCurrent = false
        val started = SystemClock.elapsedRealtime()
        if (SelfbandLog.verbose) Log.d(LOG_TAG, "proxy: making ${job.target.name} from ${job.source.name}")
        val transcoder = ProxyTranscoder(job.source, temp) { stopCurrent || paused.value || !job.source.exists() }
        val outcome = runCatching { transcoder.run() }
        val done = outcome.getOrNull() == true
        val keep = !done && outcome.isSuccess && job.source.exists() // abandoned: redo later
        when {
            // The take may have been deleted meanwhile.
            done && job.source.exists() && temp.renameTo(job.target) -> {
                val (w, h) = transcoder.size
                Log.i(LOG_TAG, "proxy: ${job.target.name} ${w}x$h in ${SystemClock.elapsedRealtime() - started} ms")
            }
            keep -> {
                temp.delete()
                Log.i(LOG_TAG, "proxy: ${job.target.name} paused")
            }
            else -> {
                temp.delete()
                outcome.exceptionOrNull()?.let {
                    Log.w(LOG_TAG, "proxy: ${job.target.name} failed", it)
                    synchronized(queue) { failed += key }
                }
            }
        }
        synchronized(queue) {
            if (!keep) queue.remove(key)
            mutableState.update { s ->
                s.copy(pending = queue.size, finished = if (job.target.exists()) s.finished + 1 else s.finished)
            }
        }
    }
}
