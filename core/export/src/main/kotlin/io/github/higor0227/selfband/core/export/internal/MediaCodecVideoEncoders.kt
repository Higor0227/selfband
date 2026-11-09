// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export.internal

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.higor0227.selfband.core.export.VideoCodec
import io.github.higor0227.selfband.core.export.VideoEncoders
import io.github.higor0227.selfband.core.export.VideoOptions
import io.github.higor0227.selfband.core.export.VideoSpec
import io.github.higor0227.selfband.core.model.value
import io.github.higor0227.selfband.core.project.ProjectRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VideoEncoders] from what the codecs declare (SPEC 1: detected, never per device):
 * MediaCodecList for encoders and bitrates, [VideoPassPlanner] for decoders.
 */
@Singleton
internal class MediaCodecVideoEncoders @Inject constructor(
    private val repository: ProjectRepository,
    private val planner: VideoPassPlanner,
) : VideoEncoders {
    private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    override fun supports(codec: VideoCodec, width: Int, height: Int, frameRate: Int): Boolean =
        encoderCaps(codec, width, height, frameRate) != null

    override fun bitRateRange(codec: VideoCodec, width: Int, height: Int, frameRate: Int): IntRange? =
        encoderCaps(codec, width, height, frameRate)?.videoCapabilities?.bitrateRange?.let { it.lower..it.upper }

    private fun encoderCaps(codec: VideoCodec, width: Int, height: Int, frameRate: Int): MediaCodecInfo.CodecCapabilities? {
        val format = MediaFormat.createVideoFormat(codec.mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        }
        val name = codecs.findEncoderForFormat(format) ?: return null
        return codecs.codecInfos.firstOrNull { it.name == name }?.getCapabilitiesForType(codec.mimeType)
    }

    override suspend fun videoPasses(projectId: String, options: VideoOptions): Int? {
        val project = repository.observe(projectId).first() ?: return null
        val spec = VideoSpec.of(project.aspectRatio.value, options)
        return planner.plan(project, spec).groups?.size ?: 0
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExportModule {
    @Binds
    abstract fun bindVideoEncoders(impl: MediaCodecVideoEncoders): VideoEncoders
}
