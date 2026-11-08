// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.camera

import io.github.higor0227.selfband.core.model.CameraLocks
import io.github.higor0227.selfband.core.model.ExposureLock
import io.github.higor0227.selfband.core.model.FocusLock
import io.github.higor0227.selfband.core.model.VideoResolution
import io.github.higor0227.selfband.core.model.WhiteBalanceLock

/** What the user chose for the camera besides lens and format. */
data class CameraControls(
    val stabilization: Boolean = false,
    val locks: CameraLocks = CameraLocks(),
)

/** How a lock can be honored by a camera, as reported by its capabilities. */
enum class LockSupport {
    /** Not available (e.g. fixed-focus lens). */
    NONE,
    /** Only the platform lock flag: it holds while the camera stays open. */
    SESSION,
    /** The measured values can be set manually, so they hold across tracks and sessions. */
    PERSISTENT,
}

/** Detected from the camera characteristics and the encoder, never from the model. */
data class CameraCapabilities(
    /** Supported resolutions with their frame rates. */
    val formats: Map<VideoResolution, List<Int>>,
    val stabilization: Boolean,
    val exposureLock: LockSupport,
    val whiteBalanceLock: LockSupport,
    val focusLock: LockSupport,
)

/** Values auto-exposure, white balance and focus are using right now (to lock them). */
data class CaptureValues(
    val exposure: ExposureLock?,
    val whiteBalance: WhiteBalanceLock?,
    val focus: FocusLock?,
)
