// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Clap test: shows the video frames around the loudest sound of the take. The middle frame
 * is where the sound is; the hands should close on it (±1 frame). Tapping another frame
 * moves the video so that frame lines up with the sound.
 */
@Composable
fun SyncCheckDialog(
    ui: SyncCheckUi?,
    mirrored: Boolean,
    onPickFrame: (Int) -> Unit,
    onResetOffset: () -> Unit,
    onClose: () -> Unit,
) {
    if (ui == null) return
    val scroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.sync_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sync_intro), style = MaterialTheme.typography.bodySmall)
                if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                ui.error?.let {
                    Text(stringResource(it.textRes()), color = MaterialTheme.colorScheme.error)
                }
                if (ui.frames.isNotEmpty()) {
                    // Start scrolled so the clap frame is visible.
                    LaunchedEffect(ui.frames.size) { scroll.scrollTo(scroll.maxValue / 2) }
                    Row(
                        Modifier.horizontalScroll(scroll),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ui.frames.forEach { frame -> FrameThumb(frame, mirrored, enabled = !ui.loading) { onPickFrame(frame.offset) } }
                    }
                }
                Text(
                    stringResource(
                        R.string.sync_video_offset,
                        String.format(Locale.ROOT, "%+.1f", ui.videoOffsetMs),
                        String.format(Locale.ROOT, "%+.2f", if (ui.frameMs > 0) ui.videoOffsetMs / ui.frameMs else 0.0),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            TextButton(onClick = onResetOffset, enabled = !ui.loading && ui.videoOffsetMs != 0.0) {
                Text(stringResource(R.string.reset))
            }
        },
    )
}

@Composable
private fun FrameThumb(frame: SyncFrame, mirrored: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val center = frame.offset == 0
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(120.dp)
                .height(160.dp)
                .border(
                    BorderStroke(if (center) 3.dp else 1.dp,
                        if (center) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                )
                .clickable(enabled = enabled && !center, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = frame.bitmap
            if (bitmap != null) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    // Show it the way it looked in the preview.
                    modifier = Modifier.padding(2.dp).graphicsLayer { scaleX = if (mirrored) -1f else 1f },
                )
            } else {
                Text("?")
            }
        }
        Text(
            if (center) stringResource(R.string.sync_clap_frame) else String.format(Locale.ROOT, "%+d", frame.offset),
            style = MaterialTheme.typography.labelSmall,
            color = if (center) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun SyncCheckError.textRes() = when (this) {
    SyncCheckError.NO_PEAK -> R.string.sync_no_peak
    SyncCheckError.NO_TIMESTAMP -> R.string.sync_no_timestamp
    SyncCheckError.OUT_OF_RANGE -> R.string.sync_out_of_range
    SyncCheckError.DECODE -> R.string.sync_decode
}
