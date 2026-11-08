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

/** Recording resolutions offered (16:9; only those the camera and encoder support). */
@Serializable
enum class VideoResolution(val width: Int, val height: Int) {
    P720(1280, 720),
    P1080(1920, 1080),
    P2160(3840, 2160),
    ;

    /** H.264 bit rate (SPEC 3.6: 1080p30 ≈ 15–20 Mbit/s); higher frame rates get 1.5×. */
    fun bitRate(frameRate: Int): Int {
        val base = when (this) {
            P720 -> 8_000_000
            P1080 -> 16_000_000
            P2160 -> 45_000_000
        }
        return if (frameRate > 30) base * 3 / 2 else base
    }
}

/** Exposure measured by auto-exposure at the moment it was locked. */
@Serializable
data class ExposureLock(val exposureTimeNs: Long, val sensitivityIso: Int)

/**
 * White balance measured by auto white balance at the moment it was locked:
 * channel gains (R, G even, G odd, B) and the 3×3 color transform as numerator/denominator pairs.
 */
@Serializable
data class WhiteBalanceLock(val gains: List<Float>, val transform: List<Int>)

/** Focus distance (diopters) at the moment it was locked. */
@Serializable
data class FocusLock(val distanceDiopters: Float)

/** Locked camera values for one lens; null = automatic. */
@Serializable
data class CameraLocks(
    val exposure: ExposureLock? = null,
    val whiteBalance: WhiteBalanceLock? = null,
    val focus: FocusLock? = null,
)

/** Camera choices of a project (SPEC 2.2), kept between tracks and sessions. */
@Serializable
data class CameraSettings(
    val lens: CameraLens = CameraLens.FRONT,
    val resolution: VideoResolution = VideoResolution.P1080,
    val frameRate: Int = 30,
    val stabilization: Boolean = false,
    val grid: Boolean = false,
    /** Track whose video is shown over the preview ("onion skin"); null = none. */
    val ghostTrackId: String? = null,
    val ghostOpacity: Float = 0.4f,
    /** Locks are per lens: values measured on one camera mean nothing on another. */
    val locks: Map<CameraLens, CameraLocks> = emptyMap(),
) {
    fun locksFor(lens: CameraLens): CameraLocks = locks[lens] ?: CameraLocks()

    fun withLocks(lens: CameraLens, transform: (CameraLocks) -> CameraLocks): CameraSettings =
        copy(locks = locks + (lens to transform(locksFor(lens))))
}
