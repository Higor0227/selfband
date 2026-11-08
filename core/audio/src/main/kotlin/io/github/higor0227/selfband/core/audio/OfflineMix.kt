// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.audio

import io.github.higor0227.selfband.core.model.EqMath
import io.github.higor0227.selfband.core.model.MixerSettings
import io.github.higor0227.selfband.core.model.Mixing
import io.github.higor0227.selfband.core.model.Project
import java.io.File

/**
 * Export render (SPEC 3.3): the same native mix graph as playback, run as fast as possible on
 * the calling thread. Render blocks in order; effect state carries over between calls.
 * Not thread-safe: use one instance from one thread, and always [close] it.
 */
class OfflineMix private constructor(private var handle: Long, private val trackList: List<PlaybackTrack>) : AutoCloseable {

    val trackCount: Int get() = trackList.size

    /** Channels of track [index]'s take (1 or 2): stems keep them. */
    fun trackChannels(index: Int): Int = nativeTrackChannels(checkOpen(), index)

    /** Clears effect state (EQ, reverb tail, limiter): call before each new pass over the timeline. */
    fun reset() = nativeReset(checkOpen())

    /** Mix of project frames [position, position + frames) as interleaved stereo into [out]. */
    fun renderMix(position: Long, frames: Int, out: FloatArray) {
        require(frames in 0..MAX_BLOCK_FRAMES && out.size >= frames * 2)
        nativeRenderMix(checkOpen(), position, frames, out)
    }

    /** Track [index] dry (as recorded) on the timeline, interleaved in its own channel count. */
    fun renderStem(index: Int, position: Long, frames: Int, out: FloatArray) {
        require(frames in 0..MAX_BLOCK_FRAMES && out.size >= frames * trackChannels(index))
        nativeRenderStem(checkOpen(), index, position, frames, out)
    }

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    private fun checkOpen(): Long = handle.also { check(it != 0L) { "OfflineMix closed" } }

    companion object {
        const val MAX_BLOCK_FRAMES = 4096

        /** Opens the render for [tracks] with the master settings of [mixer]. Null if a take is unreadable. */
        fun open(sampleRate: Int, tracks: List<PlaybackTrack>, mixer: MixerSettings): OfflineMix? {
            val handle = nativeOpen(
                sampleRate,
                tracks.map { it.file.absolutePath }.toTypedArray(),
                tracks.map { it.startFrame }.toLongArray(),
                tracks.flatMap { listOf(it.gain.left, it.gain.right) }.toFloatArray(),
                tracks.flatMap { EqMath.nativeParams(it.eq).asList() }.toFloatArray(),
                tracks.map { it.send }.toFloatArray(),
                Mixing.reverbParams(mixer.reverb),
                Mixing.masterGain(mixer),
                mixer.limiter,
            )
            return if (handle == 0L) null else OfflineMix(handle, tracks)
        }

        init {
            System.loadLibrary("selfband_audio")
        }

        @JvmStatic private external fun nativeOpen(
            sampleRate: Int, paths: Array<String>, startFrames: LongArray, gains: FloatArray, eqs: FloatArray,
            sends: FloatArray, reverb: FloatArray, masterGain: Float, limiter: Boolean,
        ): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeTrackChannels(handle: Long, index: Int): Int
        @JvmStatic private external fun nativeRenderMix(handle: Long, position: Long, frames: Int, out: FloatArray)
        @JvmStatic private external fun nativeRenderStem(handle: Long, index: Int, position: Long, frames: Int, out: FloatArray)
    }
}

/** The project's playable tracks as the mix graph sees them; shared by playback and export. */
object PlaybackTracks {
    /** Track ids and their engine tracks, for every track whose active take has audio. */
    fun of(project: Project, fileOf: (trackId: String, fileName: String) -> File): List<Pair<String, PlaybackTrack>> {
        val anySolo = Mixing.anySolo(project)
        return project.tracks.mapNotNull { track ->
            val take = track.activeTake?.takeIf { it.lengthFrames > 0 } ?: return@mapNotNull null
            track.id to PlaybackTrack(
                file = fileOf(track.id, take.audioFile),
                startFrame = take.startFrame,
                gain = Mixing.trackGain(track, anySolo),
                eq = track.eq,
                send = Mixing.sendGain(track),
            )
        }
    }
}
