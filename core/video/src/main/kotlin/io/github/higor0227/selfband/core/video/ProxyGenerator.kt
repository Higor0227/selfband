// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.video

import io.github.higor0227.selfband.core.model.Project
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Progress of the background proxy work. */
data class ProxyState(
    /** Proxies waiting or being made. */
    val pending: Int = 0,
    /** Bumped every time a proxy file is finished (scenes then pick it up). */
    val finished: Int = 0,
)

/**
 * Makes the proxies of take videos in the background (SPEC 3.5, 3.6): 540p H.264 with short
 * GOP and the original's frame timing, written next to the original as
 * `tracks/<trackId>/<takeId>.proxy.mp4`. One at a time, at low priority.
 */
interface ProxyGenerator {
    val state: StateFlow<ProxyState>

    /**
     * Queues the missing proxies of [project]'s active takes. [file] resolves a file name
     * inside a track's directory. Cheap to call on every project change.
     */
    fun request(project: Project, file: (trackId: String, fileName: String) -> File)

    /**
     * While paused (e.g. recording, so the camera's encoder and the disk are not shared), the
     * proxy being made is abandoned and nothing starts; it is redone after resuming.
     */
    fun setPaused(paused: Boolean)
}
