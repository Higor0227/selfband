// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export

/** What to export (SPEC 2.7). */
data class ExportOptions(
    /** The canvas as MP4 with the mix as AAC: the main export. */
    val video: Boolean = true,
    val videoOptions: VideoOptions = VideoOptions(),
    /** The stereo mix (same graph as playback, metronome excluded) as WAV. */
    val mixWav: Boolean = false,
    /** One WAV per track: dry (as recorded), aligned and trimmed, all the same length. */
    val stems: Boolean = false,
    val wavFormat: WavFormat = WavFormat.PCM_24,
) {
    val isEmpty: Boolean get() = !video && !mixWav && !stems
}

enum class ExportStep { VIDEO, MIX, STEMS }

/** Video codecs (SPEC 2.7): H.264 for compatibility (default), HEVC for smaller files. */
enum class VideoCodec(val mimeType: String, internal val bitRateFactor: Double) {
    H264("video/avc", 1.0),
    /** About 40 % fewer bits for the same quality. */
    HEVC("video/hevc", 0.6),
}

/** Output sizes by their shorter side (SPEC 2.7). */
enum class VideoResolution(val shortSide: Int) { P720(720), P1080(1080), P2160(2160) }

/** The user's video choices (SPEC 2.7). */
data class VideoOptions(
    val codec: VideoCodec = VideoCodec.H264,
    val resolution: VideoResolution = VideoResolution.P1080,
    val frameRate: Int = VideoSpec.DEFAULT_FRAME_RATE,
    /** Bits per second; null = automatic for the size, rate and codec. */
    val bitRate: Int? = null,
) {
    companion object {
        val FRAME_RATES = listOf(30, 60)
    }
}

/** Resolved video encoding settings. */
data class VideoSpec(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRate: Int,
    val mimeType: String = MIME_AVC,
) {
    companion object {
        const val MIME_AVC = "video/avc"
        const val DEFAULT_SHORT_SIDE = 1080
        const val DEFAULT_FRAME_RATE = 30
        const val AUDIO_BIT_RATE = 256_000
        /** Bits per pixel per frame for the automatic H.264 bitrate: ~12 Mbps at 1080p30. */
        private const val BITS_PER_PIXEL = 0.2

        /** [options] applied to a canvas of [aspect] (width / height). */
        fun of(aspect: Float, options: VideoOptions): VideoSpec {
            val auto = forAspect(aspect, options.resolution.shortSide, options.frameRate, options.codec)
            return options.bitRate?.let { auto.copy(bitRate = it) } ?: auto
        }

        /** Output for a canvas of [aspect] (width / height): the shorter side is [shortSide], sides even. */
        fun forAspect(
            aspect: Float,
            shortSide: Int = DEFAULT_SHORT_SIDE,
            frameRate: Int = DEFAULT_FRAME_RATE,
            codec: VideoCodec = VideoCodec.H264,
        ): VideoSpec {
            val short = even(shortSide.toFloat())
            val (w, h) = if (aspect >= 1f) {
                even(short * aspect) to short
            } else {
                short to even(short / aspect)
            }
            return VideoSpec(w, h, frameRate, autoBitRate(w, h, frameRate, codec), codec.mimeType)
        }

        fun autoBitRate(width: Int, height: Int, frameRate: Int, codec: VideoCodec = VideoCodec.H264): Int {
            val bps = width.toDouble() * height * frameRate * BITS_PER_PIXEL * codec.bitRateFactor
            return (Math.round(bps / 500_000.0) * 500_000).toInt().coerceAtLeast(1_000_000)
        }

        /** Approximate MP4 size for [seconds] of [spec] (video + AAC). */
        fun estimatedBytes(spec: VideoSpec, seconds: Double): Long =
            ((spec.bitRate + AUDIO_BIT_RATE) * seconds / 8).toLong()

        private fun even(value: Float): Int = (Math.round(value / 2f) * 2).coerceAtLeast(2)
    }
}

/** What the device can encode (SPEC 2.7: "4K when supported") and decode at once (SPEC 3.5). */
interface VideoEncoders {
    /** True if an encoder takes [codec] at [width] × [height] and [frameRate] fps. */
    fun supports(codec: VideoCodec, width: Int, height: Int, frameRate: Int): Boolean

    /** Bitrates (bits/s) that encoder accepts, or null if there is none. */
    fun bitRateRange(codec: VideoCodec, width: Int, height: Int, frameRate: Int): IntRange?

    /**
     * Whether all of [projectId]'s videos can be decoded at the same time, as the export needs.
     * Null when they fit. Reads the files: call off the main thread.
     */
    suspend fun decoderLimit(projectId: String): DecoderLimit?
}

/** More videos ([needed]) than simultaneous decoders of their kind ([available]). */
data class DecoderLimit(val needed: Int, val available: Int)

enum class ExportFailure {
    /** Nothing to export: no recorded audio, or an empty trim. */
    EMPTY_PROJECT,
    /** A take file is missing or unreadable. */
    UNREADABLE_TAKE,
    /** The output could not be written (storage full, MediaStore refused). */
    WRITE_FAILED,
    /** No encoder for the video format on this device, or encoding failed. */
    VIDEO_FAILED,
}

/** A saved file, as MediaStore sees it. */
data class ExportOutput(val uri: String, val displayName: String, val mimeType: String)

sealed interface ExportState {
    data object Idle : ExportState

    data class Running(
        val projectId: String,
        val projectName: String,
        val step: ExportStep,
        /** 1-based stem being rendered while [step] is STEMS. */
        val stemIndex: Int = 0,
        val stemCount: Int = 0,
        /** 0..1 over the whole export. */
        val progress: Float = 0f,
    ) : ExportState

    data class Done(
        val projectId: String,
        val projectName: String,
        /** Where the files went, e.g. "Movies/Selfband/" and "Music/Selfband/My song/". */
        val folders: List<String>,
        val outputs: List<ExportOutput>,
    ) : ExportState

    data class Failed(val projectId: String, val reason: ExportFailure, val detail: String? = null) : ExportState

    data class Cancelled(val projectId: String) : ExportState
}

object ExportNames {
    private val forbidden = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /** A name safe for a file or folder: forbidden characters replaced, trimmed, at most 60 chars. */
    fun sanitize(name: String, fallback: String = "Selfband"): String {
        val cleaned = name.replace(forbidden, "_").trim().trim('.').take(60).trim()
        return cleaned.ifEmpty { fallback }
    }
}
