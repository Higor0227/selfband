// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable

/** Canvas aspect ratio of a project (used from Phase 3 on). */
@Serializable
enum class AspectRatio { PORTRAIT_9_16, LANDSCAPE_16_9, SQUARE_1_1 }

/**
 * A Selfband project. Persisted as `project.json` (see :core:project).
 * Bump [CURRENT_SCHEMA_VERSION] and add a migration on every schema change.
 *
 * Timeline unit: frames at [sampleRate]. The audio frame position is the master clock.
 */
@Serializable
data class Project(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Fixed at creation (SPEC section 2.1). */
    val sampleRate: Int,
    val aspectRatio: AspectRatio = AspectRatio.PORTRAIT_9_16,
    val metronome: MetronomeSettings = MetronomeSettings(),
    val camera: CameraSettings = CameraSettings(),
    val canvas: CanvasSettings = CanvasSettings(),
    val mixer: MixerSettings = MixerSettings(),
    val tracks: List<Track> = emptyList(),
) {
    /** Project length = end of the longest active take, in frames. */
    val lengthFrames: Long
        get() = tracks.maxOfOrNull { it.activeTake?.endFrame ?: 0L }?.coerceAtLeast(0L) ?: 0L

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class Track(
    val id: String,
    val name: String,
    val takes: List<Take> = emptyList(),
    val activeTakeId: String? = null,
    val muted: Boolean = false,
    /** Track volume in dB (fader). [Levels.MIN_DB] or below means silence. */
    val volumeDb: Double = 0.0,
    /** −1 = left … 0 = center … +1 = right (see [Mixing.pan]). */
    val pan: Float = 0f,
    /** While any track is soloed, only soloed tracks are heard (mute still wins). */
    val solo: Boolean = false,
) {
    val activeTake: Take?
        get() = takes.firstOrNull { it.id == activeTakeId } ?: takes.lastOrNull()
}

/**
 * One recording of a track. The WAV file is never rewritten: alignment is stored here
 * and applied at playback/export time (non-destructive, SPEC section 3.4).
 */
@Serializable
data class Take(
    val id: String,
    /** File name relative to the track directory, e.g. "<takeId>.wav". */
    val audioFile: String,
    val channelCount: Int,
    /** Length in frames; [LENGTH_UNKNOWN] while recording (recovered after a crash). */
    val lengthFrames: Long,
    /** Project position at which recording started (the playhead when the take began). */
    val recordStartFrame: Long,
    /** Automatic alignment in frames (negative = earlier), from timestamps/calibration. */
    val autoOffsetFrames: Long,
    /** User adjustment in frames, added on top of [autoOffsetFrames]. */
    val manualOffsetFrames: Long = 0,
    val alignment: AlignmentInfo,
    val createdAtMillis: Long,
    /** Null for audio-only takes (or when the camera was unavailable). */
    val video: TakeVideo? = null,
) {
    /** Project position of the take's first sample. */
    val startFrame: Long get() = recordStartFrame + autoOffsetFrames + manualOffsetFrames
    val endFrame: Long get() = startFrame + lengthFrames.coerceAtLeast(0)

    companion object {
        const val LENGTH_UNKNOWN = -1L
    }
}

/** How [Take.autoOffsetFrames] was computed, kept for diagnostics and recalculation. */
@Serializable
data class AlignmentInfo(
    val method: AlignmentMethod,
    /** Measured round-trip from stream timestamps/estimates, in frames (positive). */
    val measuredRoundTripFrames: Double,
    /** Calibrated residual latency added on top, in frames (Phase 1c). */
    val calibrationResidualFrames: Double = 0.0,
    val outputLatencyEstimateMs: Double? = null,
    val inputLatencyEstimateMs: Double? = null,
    val xrunsDuringTake: Int = 0,
    val droppedFrames: Long = 0,
    /** Device profile whose calibration was applied (null = none). */
    val profileKey: String? = null,
    val calibrationMethod: CalibrationMethod? = null,
)

@Serializable
enum class AlignmentMethod {
    /** Input/output hardware timestamps (preferred). */
    TIMESTAMPS,
    /** Oboe latency estimates (fallback when timestamps are unavailable). */
    ESTIMATE,
}
