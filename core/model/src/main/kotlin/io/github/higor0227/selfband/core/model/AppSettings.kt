// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.model

import kotlinx.serialization.Serializable

/** When the preview decodes the light copies (proxies) instead of the originals (SPEC 3.5). */
@Serializable
enum class ProxyMode {
    /** From [Proxies.AUTO_FROM_VIDEOS] videos, or when the device cannot keep up. */
    AUTO,
    ALWAYS,
    OFF,
}

/** App theme (SPEC 2.8). */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Video codecs (SPEC 2.7): H.264 for compatibility (default), HEVC for smaller files. */
@Serializable
enum class VideoCodec(val mimeType: String, val bitRateFactor: Double) {
    H264("video/avc", 1.0),
    /** About 40 % fewer bits for the same quality. */
    HEVC("video/hevc", 0.6),
}

/** Camera choices a new project starts with (SPEC 2.8: camera and recording quality). */
@Serializable
data class CameraDefaults(
    val lens: CameraLens = CameraLens.FRONT,
    val resolution: VideoResolution = VideoResolution.P1080,
    val frameRate: Int = 30,
    val stabilization: Boolean = false,
) {
    companion object {
        val FRAME_RATES = listOf(30, 60)
    }
}

/** What the export screen starts with (SPEC 2.8: export quality). */
@Serializable
data class ExportDefaults(
    val video: Boolean = true,
    val codec: VideoCodec = VideoCodec.H264,
    /** Shorter side of the video: 720, 1080 or 2160. */
    val shortSide: Int = 1080,
    val frameRate: Int = 30,
    val mixWav: Boolean = false,
    val stems: Boolean = false,
    /** WAV as 32-bit float instead of 24-bit PCM. */
    val wavFloat: Boolean = false,
) {
    companion object {
        val SHORT_SIDES = listOf(720, 1080, 2160)
        val FRAME_RATES = listOf(30, 60)
    }
}

/**
 * App-wide settings (SPEC 2.8, 3.5, 3.8), stored as `settings.json` by :core:project. Unknown
 * keys are ignored and missing ones take these defaults, so fields can be added without a
 * migration. The app language is kept by the platform (per-app language), not here.
 */
@Serializable
data class AppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val proxyMode: ProxyMode = ProxyMode.AUTO,
    /**
     * Videos the export decodes at once before composing in several passes (SPEC 3.5); null
     * = what the device's decoders declare.
     */
    val exportDecodersPerPass: Int? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Debug-level lines in the log (SPEC 3.8). */
    val verboseLog: Boolean = false,
    /** Canvas format of new projects. */
    val aspectRatio: AspectRatio = AspectRatio.PORTRAIT_9_16,
    /** Metronome and count-in of new projects. */
    val metronome: MetronomeSettings = MetronomeSettings(),
    val camera: CameraDefaults = CameraDefaults(),
    val export: ExportDefaults = ExportDefaults(),
    /** Folder under Movies/ and Music/ where exports go. */
    val exportFolder: String = ExportFolder.DEFAULT,
) {
    /** A just-created [project] with these defaults applied. */
    fun applyTo(project: Project): Project = project.copy(
        aspectRatio = aspectRatio,
        metronome = metronome.normalized(),
        camera = project.camera.copy(
            lens = camera.lens,
            resolution = camera.resolution,
            frameRate = camera.frameRate,
            stabilization = camera.stabilization,
        ),
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /** Choices offered for [exportDecodersPerPass] besides automatic. */
        val DECODERS_PER_PASS_CHOICES = listOf(8, 4)
    }
}

/** The export folder name (SPEC 2.8), used as `Movies/<name>/` and `Music/<name>/<project>/`. */
object ExportFolder {
    const val DEFAULT = "Selfband"
    const val MAX_LENGTH = 40

    /** A single safe folder name: no separators or reserved characters; [DEFAULT] if empty. */
    fun sanitize(name: String): String {
        val cleaned = name.trim()
            .map { c -> if (c in "/\\:*?\"<>|" || c.isISOControl()) '_' else c }
            .joinToString("")
            .trim('.', ' ')
            .take(MAX_LENGTH)
            .trim()
        return cleaned.ifEmpty { DEFAULT }
    }
}

/** Proxies: light copies of the take videos for the preview (SPEC 3.5, 3.6). */
object Proxies {
    /** Short side of a proxy, in pixels ("540p"). */
    const val SHORT_SIDE = 540

    /** In [ProxyMode.AUTO], proxies are used from this many videos on ("~8 tracks"). */
    const val AUTO_FROM_VIDEOS = 8

    /** Seconds between key frames: short, so seeking and scrubbing stay quick. */
    const val KEY_FRAME_INTERVAL_S = 0.25f

    /** `tracks/<trackId>/<takeId>.proxy.mp4`. */
    fun fileName(takeId: String): String = "$takeId.proxy.mp4"

    /**
     * Encoded size of the proxy of a [width] × [height] video: the short side becomes
     * [SHORT_SIDE] (never upscaled), the aspect is kept and both sides are even.
     */
    fun size(width: Int, height: Int): Pair<Int, Int> {
        val short = minOf(width, height)
        if (short <= 0) return 0 to 0
        val scale = minOf(1.0, SHORT_SIDE.toDouble() / short)
        fun even(v: Double) = (Math.round(v / 2.0) * 2).toInt().coerceAtLeast(2)
        return even(width * scale) to even(height * scale)
    }

    /** Bits per second of a proxy: about 0.12 bits per pixel per frame (≈ 1.9 Mbps at 540p30). */
    fun bitRate(width: Int, height: Int, frameRate: Int): Int =
        (width.toLong() * height * frameRate.coerceIn(1, 240) * 0.12).toInt().coerceAtLeast(250_000)

    /**
     * Whether the preview should use proxies for [videos] videos at once. [overCapacity]: the
     * originals need more simultaneous decoders than the device has (`getMaxSupportedInstances`);
     * [slow]: the preview measured that the originals cannot be decoded in time.
     */
    fun use(mode: ProxyMode, videos: Int, overCapacity: Boolean, slow: Boolean): Boolean = when (mode) {
        ProxyMode.ALWAYS -> videos > 0
        ProxyMode.OFF -> false
        ProxyMode.AUTO -> videos > 0 && (videos >= AUTO_FROM_VIDEOS || overCapacity || slow)
    }
}
