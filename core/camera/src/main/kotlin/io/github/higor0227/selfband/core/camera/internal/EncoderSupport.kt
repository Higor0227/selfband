// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.camera.internal

import android.media.MediaCodecList
import android.media.MediaFormat

/** Asks the platform's H.264 encoders whether a size and frame rate can be encoded. */
internal object EncoderSupport {
    private val encoders by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes.map { t -> t.lowercase() } }
            .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
    }

    fun supports(width: Int, height: Int, frameRate: Int): Boolean =
        encoders.any { runCatching { it.areSizeAndRateSupported(width, height, frameRate.toDouble()) }.getOrDefault(false) }
}
