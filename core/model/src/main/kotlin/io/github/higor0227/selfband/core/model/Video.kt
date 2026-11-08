// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable

/** Which way a camera faces, as reported by the platform (never by model). */
@Serializable
enum class CameraLens { BACK, FRONT, EXTERNAL }

/** Clock of the camera's sensor timestamps, detected at runtime (SPEC 3.4). */
@Serializable
enum class VideoClock {
    /** Same clock as the audio timestamps (CLOCK_MONOTONIC). */
    MONOTONIC,
    /** CLOCK_BOOTTIME (elapsedRealtime); converted to MONOTONIC when recording. */
    BOOTTIME,
    /** Could not be matched to a system clock: alignment is approximate. */
    UNKNOWN,
}

/**
 * The video of a take (no audio; the take's audio is the WAV). Like the audio, the file is
 * never rewritten: its place on the timeline is stored here.
 */
@Serializable
data class TakeVideo(
    /** File name relative to the track directory, e.g. "<takeId>.mp4". */
    val file: String,
    val lens: CameraLens,
    /** Encoded size, before applying [rotationDegrees]. */
    val width: Int,
    val height: Int,
    /** Rotation to apply for display (also stored in the MP4). */
    val rotationDegrees: Int,
    /** True if the preview was mirrored (front camera); the file itself is not mirrored. */
    val previewMirrored: Boolean,
    val frameRate: Int,
    val frameCount: Int,
    val durationUs: Long,
    /**
     * Project position (frames at the project rate) of the first video frame, from the camera
     * timestamp mapped onto the audio clock. Null when it could not be measured.
     */
    val startPositionFrames: Double?,
    /** User adjustment, independent of the audio offset (SPEC 3.4). */
    val manualOffsetMs: Double = 0.0,
    val clock: VideoClock,
) {
    /** Project position of the first frame including the manual offset. */
    fun startFrame(sampleRate: Int): Double? =
        startPositionFrames?.let { it + manualOffsetMs * sampleRate / 1000.0 }
}
