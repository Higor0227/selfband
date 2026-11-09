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

/**
 * App-wide settings (SPEC 2.8), stored as `settings.json` by :core:project. Unknown keys are
 * ignored and missing ones take these defaults, so fields can be added without a migration.
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
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /** Choices offered for [exportDecodersPerPass] besides automatic. */
        val DECODERS_PER_PASS_CHOICES = listOf(8, 4)
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
