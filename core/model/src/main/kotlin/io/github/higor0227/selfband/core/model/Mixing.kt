// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable

/** Master bus settings (SPEC 2.5). */
@Serializable
data class MixerSettings(
    /** Master volume in dB, applied before the limiter. */
    val masterVolumeDb: Double = 0.0,
    /** Clipping protection on the master (a technical safeguard, on by default). */
    val limiter: Boolean = true,
    val reverb: ReverbSettings = ReverbSettings(),
)

/** Left/right linear gains of a track on the master bus. */
data class StereoGain(val left: Float, val right: Float) {
    companion object {
        val SILENT = StereoGain(0f, 0f)
    }
}

/** Mixer math shared by playback and (later) export, so both sound the same. */
object Mixing {
    const val MIN_MASTER_DB = -60.0
    const val MAX_MASTER_DB = 12.0
    const val MAX_SEND_DB = 6.0

    /**
     * Balance pan law: centered (0) leaves both sides at unity, so a centered track sounds
     * exactly as without pan; moving toward one side only turns the other side down, reaching
     * silence at ±1. Never boosts, so panning cannot push the mix into the limiter.
     */
    fun pan(pan: Float): StereoGain {
        val p = pan.coerceIn(-1f, 1f)
        return StereoGain(left = if (p > 0f) 1f - p else 1f, right = if (p < 0f) 1f + p else 1f)
    }

    /** True when any track is soloed: then only soloed tracks are heard. */
    fun anySolo(project: Project): Boolean = project.tracks.any { it.solo }

    /**
     * What [track] contributes: volume, pan and polarity, silenced by mute (which wins) or by
     * others' solo. Negative gains mean the phase is inverted.
     */
    fun trackGain(track: Track, anySolo: Boolean): StereoGain {
        if (track.muted || (anySolo && !track.solo)) return StereoGain.SILENT
        val g = Levels.dbToLinear(track.volumeDb).toFloat() * if (track.phaseInverted) -1f else 1f
        val p = pan(track.pan)
        return StereoGain(g * p.left, g * p.right)
    }

    fun trackGain(project: Project, track: Track): StereoGain = trackGain(track, anySolo(project))

    /** Linear reverb send. It is post-fader, so a muted or silenced track sends nothing. */
    fun sendGain(track: Track): Float =
        if (track.reverbSendDb <= Levels.MIN_DB) 0f else Levels.dbToLinear(track.reverbSendDb).toFloat()

    fun reverbReturn(reverb: ReverbSettings): Float =
        if (reverb.returnDb <= ReverbSettings.MIN_RETURN_DB) 0f else Levels.dbToLinear(reverb.returnDb).toFloat()

    /**
     * Native parameter block, in the order of Reverb::Param in Reverb.h:
     * size, decaySeconds, preDelayMs, damping, returnGain.
     */
    fun reverbParams(reverb: ReverbSettings): FloatArray = floatArrayOf(
        reverb.size.toFloat(),
        reverb.decaySeconds.toFloat(),
        reverb.preDelayMs.toFloat(),
        reverb.damping.toFloat(),
        reverbReturn(reverb),
    )

    fun masterGain(mixer: MixerSettings): Float =
        if (mixer.masterVolumeDb <= MIN_MASTER_DB) 0f else Levels.dbToLinear(mixer.masterVolumeDb).toFloat()
}
