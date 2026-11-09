// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.export

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.higor0227.selfband.core.model.VideoCodec
import io.github.higor0227.selfband.core.export.VideoOptions
import io.github.higor0227.selfband.core.export.VideoResolution
import io.github.higor0227.selfband.core.export.VideoSpec
import kotlin.math.roundToInt

/** Callbacks of the video options. */
internal class VideoActions(
    val onVideo: (Boolean) -> Unit,
    val onCodec: (VideoCodec) -> Unit,
    val onResolution: (VideoResolution) -> Unit,
    val onFrameRate: (Int) -> Unit,
    /** Bits per second; null = automatic. */
    val onBitRate: (Int?) -> Unit,
)

/** Video export options (SPEC 2.7): codec, size, frame rate and bitrate, limited to what the device encodes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoCard(state: ExportUiState, fileName: String, actions: VideoActions) {
    val options = state.options
    val video = options.videoOptions
    val modes = state.modes
    val spec = state.videoSpec
    /** A value is offered when some supported mode has it; the other choices then adapt. */
    fun offered(test: (VideoMode) -> Boolean) = modes == null || modes.keys.any(test)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.export_video_section), style = MaterialTheme.typography.titleSmall)
            CheckRow(stringResource(R.string.export_video), stringResource(R.string.export_video_hint), options.video, actions.onVideo)
            if (options.video) {
                Label(stringResource(R.string.export_codec))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoCodec.entries.forEach { codec ->
                        FilterChip(
                            selected = video.codec == codec,
                            enabled = offered { it.codec == codec },
                            onClick = { actions.onCodec(codec) },
                            label = { Text(stringResource(codec.label)) },
                        )
                    }
                }
                Label(stringResource(R.string.export_resolution))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoResolution.entries.forEach { resolution ->
                        FilterChip(
                            selected = video.resolution == resolution,
                            enabled = offered { it.resolution == resolution },
                            onClick = { actions.onResolution(resolution) },
                            label = { Text(stringResource(resolution.label)) },
                        )
                    }
                }
                Label(stringResource(R.string.export_frame_rate))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoOptions.FRAME_RATES.forEach { fps ->
                        FilterChip(
                            selected = video.frameRate == fps,
                            enabled = offered { it.frameRate == fps },
                            onClick = { actions.onFrameRate(fps) },
                            label = { Text(stringResource(R.string.export_fps, fps)) },
                        )
                    }
                }
                Label(stringResource(R.string.export_bitrate))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = video.bitRate == null,
                        onClick = { actions.onBitRate(null) },
                        label = { Text(stringResource(R.string.export_bitrate_auto)) },
                    )
                    FilterChip(
                        selected = video.bitRate != null,
                        onClick = { if (video.bitRate == null) actions.onBitRate(spec.bitRate) },
                        label = { Text(stringResource(R.string.export_bitrate_manual)) },
                    )
                }
                val manual = video.bitRate
                if (manual != null) {
                    val range = modes?.get(video.mode)
                    val low = maxOf(MIN_MANUAL_BPS, range?.first ?: MIN_MANUAL_BPS)
                    val high = minOf(MAX_MANUAL_BPS, range?.last ?: MAX_MANUAL_BPS).coerceAtLeast(low + STEP_BPS)
                    Slider(
                        value = manual.coerceIn(low, high).toFloat(),
                        onValueChange = { actions.onBitRate(((it / STEP_BPS).roundToInt() * STEP_BPS).coerceIn(low, high)) },
                        valueRange = low.toFloat()..high.toFloat(),
                    )
                }
                val seconds = state.project?.let { (it.trimEnd - it.trimStart).toDouble() / it.sampleRate } ?: 0.0
                Text(
                    stringResource(
                        R.string.export_video_summary, spec.width, spec.height, mbps(spec.bitRate),
                        Formatter.formatShortFileSize(LocalContext.current, VideoSpec.estimatedBytes(spec, seconds)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val takeFps = state.project?.tracks?.mapNotNull { it.activeTake?.video?.frameRate }?.maxOrNull()
                if (takeFps != null && takeFps < video.frameRate) {
                    Hint(stringResource(R.string.export_fps_repeat, takeFps, video.frameRate))
                }
                if (modes != null && modes.size < VideoCodec.entries.size * VideoResolution.entries.size * VideoOptions.FRAME_RATES.size) {
                    Hint(stringResource(R.string.export_unsupported_hint))
                }
                when (val passes = state.videoPasses) {
                    null, 1 -> Unit
                    0 -> Text(
                        stringResource(R.string.export_decoder_limit),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Hint(stringResource(R.string.export_passes_hint, passes))
                }
            }
            Text(stringResource(R.string.export_destination, "Movies/${state.folder}/$fileName.mp4"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private val VideoCodec.label: Int
    get() = when (this) {
        VideoCodec.H264 -> R.string.export_codec_h264
        VideoCodec.HEVC -> R.string.export_codec_hevc
    }

private val VideoResolution.label: Int
    get() = when (this) {
        VideoResolution.P720 -> R.string.export_res_720
        VideoResolution.P1080 -> R.string.export_res_1080
        VideoResolution.P2160 -> R.string.export_res_2160
    }

/** "12.5" / "12" (Mbps), with the device's decimal separator. */
internal fun mbps(bitRate: Int): String {
    val value = bitRate / 1_000_000.0
    return if (value == Math.floor(value)) value.toInt().toString() else "%.1f".format(value)
}

private const val MIN_MANUAL_BPS = 1_000_000
private const val MAX_MANUAL_BPS = 200_000_000
private const val STEP_BPS = 500_000
