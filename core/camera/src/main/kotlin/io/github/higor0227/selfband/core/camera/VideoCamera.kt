// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.camera

import android.view.Surface
import io.github.higor0227.selfband.core.model.CameraLens
import io.github.higor0227.selfband.core.model.VideoClock
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Video capture without audio (SPEC 2.2, 3.4). The implementation is hidden behind this
 * interface so it can be swapped (Camera2 today; CameraX if it ever exposes what we need).
 *
 * Every recording reports the CLOCK_MONOTONIC time of its first frame, so the caller can
 * place the video on the audio timeline.
 */
interface VideoCamera {
    val state: StateFlow<CameraState>

    /** Lenses present on this device, as reported by the platform. */
    fun availableLenses(): List<CameraLens>

    /** Formats, stabilization and lock support of the camera facing [lens]; null if none. */
    fun capabilities(lens: CameraLens): CameraCapabilities?

    /** Stabilization and locks; kept across [open] calls and applied immediately if open. */
    suspend fun setControls(controls: CameraControls)

    /** Values the camera is using right now (latest frame), for locking them. */
    fun currentValues(): CaptureValues?

    /** Opens the camera facing [lens]. Requires the CAMERA permission. */
    suspend fun open(lens: CameraLens, config: VideoConfig = VideoConfig()): Boolean

    /**
     * Sets the preview target (null = no preview). The surface should be sized to
     * [CameraState.previewSize].
     */
    suspend fun setPreviewSurface(surface: Surface?)

    /**
     * Starts encoding into [file] (MP4, H.264, no audio). [displayRotationDegrees] is the
     * current display rotation, used to store the right orientation in the file.
     */
    suspend fun startRecording(file: File, displayRotationDegrees: Int): Boolean

    /** Stops and finalizes the file. Null if nothing was recorded. */
    suspend fun stopRecording(): VideoRecording?

    suspend fun close()
}

data class PixelSize(val width: Int, val height: Int)

data class VideoConfig(
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,
    /** SPEC 3.6: 1080p30 ≈ 15–20 Mbps. */
    val bitRate: Int = 16_000_000,
)

data class CameraState(
    val isOpen: Boolean = false,
    val lens: CameraLens? = null,
    val videoSize: PixelSize? = null,
    val previewSize: PixelSize? = null,
    val sensorOrientation: Int = 0,
    val frameRate: Int = 0,
    val clock: VideoClock = VideoClock.UNKNOWN,
    val recording: Boolean = false,
    val framesRecorded: Int = 0,
    val error: String? = null,
)

data class VideoRecording(
    val file: File,
    val lens: CameraLens,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val frameRate: Int,
    val frameCount: Int,
    val durationUs: Long,
    /**
     * CLOCK_MONOTONIC time (ns) at the middle of the first frame's exposure (center row).
     * Null when the sensor clock could not be matched to a system clock.
     */
    val firstFrameMonotonicNanos: Long?,
    val clock: VideoClock,
)
