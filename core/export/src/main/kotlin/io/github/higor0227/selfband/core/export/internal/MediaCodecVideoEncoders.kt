// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.export.internal

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.higor0227.selfband.core.export.DecoderLimit
import io.github.higor0227.selfband.core.export.VideoCodec
import io.github.higor0227.selfband.core.export.VideoEncoders
import io.github.higor0227.selfband.core.project.ProjectRepository
import io.github.higor0227.selfband.core.video.CanvasScenes
import io.github.higor0227.selfband.core.video.DecoderInstances
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VideoEncoders] from what the codecs declare (SPEC 1: detected, never per device):
 * MediaCodecList for encoders and bitrates, [DecoderInstances] for decoders.
 */
@Singleton
internal class MediaCodecVideoEncoders @Inject constructor(
    private val repository: ProjectRepository,
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

    override suspend fun decoderLimit(projectId: String): DecoderLimit? {
        val project = repository.observe(projectId).first() ?: return null
        val files = CanvasScenes.build(
            project,
            { trackId, name -> repository.takeAudioFile(project.id, trackId, name) },
            { name -> repository.projectMediaFile(project.id, name) },
        ).decodedLayers.map { it.file }
        return DecoderInstances.shortfall(files)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExportModule {
    @Binds
    abstract fun bindVideoEncoders(impl: MediaCodecVideoEncoders): VideoEncoders
}
