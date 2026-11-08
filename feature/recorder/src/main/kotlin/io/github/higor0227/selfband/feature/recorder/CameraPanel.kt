// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.higor0227.selfband.core.camera.PixelSize
import io.github.higor0227.selfband.core.model.CameraLens
import kotlin.math.min

/** Camera preview and video controls above the track list. */
@Composable
fun CameraPanel(
    state: RecorderUiState,
    onPreviewSurface: (Surface?) -> Unit,
    onSwitchLens: () -> Unit,
    onVideoEnabled: (Boolean) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val height = (configuration.screenHeightDp * if (landscape) 0.45f else 0.32f).dp
    val cam = state.camera
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(height).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val previewSize = cam.previewSize
            val error = cam.error
            when {
                !state.cameraPermission -> PanelMessage(stringResource(R.string.camera_permission_needed))
                !state.videoEnabled -> PanelMessage(stringResource(R.string.video_off))
                error != null && !cam.isOpen -> PanelMessage(stringResource(R.string.camera_error, error))
                !cam.isOpen || previewSize == null -> PanelMessage(stringResource(R.string.camera_opening))
                else -> CameraPreview(previewSize, cam.sensorOrientation, onPreviewSurface)
            }
            if (cam.recording) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).width(12.dp).height(12.dp)
                        .background(MaterialTheme.colorScheme.error, MaterialTheme.shapes.small),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.video_toggle), style = MaterialTheme.typography.labelLarge)
            Switch(
                checked = state.videoEnabled,
                onCheckedChange = onVideoEnabled,
                enabled = state.isIdle && state.cameraPermission,
            )
            Spacer(Modifier.weight(1f))
            if (state.videoEnabled && cam.isOpen) {
                val size = cam.videoSize
                Text(
                    (size?.let { "${it.width}×${it.height} · " } ?: "") + "${cam.frameRate} fps",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(
                onClick = onSwitchLens,
                enabled = state.isIdle && state.videoEnabled && state.availableLenses.size > 1,
            ) {
                Text(stringResource(R.string.switch_camera, stringResource(state.lens.labelRes())))
            }
        }
    }
}

@Composable
private fun PanelMessage(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}

/**
 * TextureView preview. Camera2 already rotates (and, for the front camera, mirrors) the
 * preview into the display's natural orientation; this only compensates the current display
 * rotation and keeps the aspect ratio.
 */
@Composable
private fun CameraPreview(previewSize: PixelSize, sensorOrientation: Int, onSurface: (Surface?) -> Unit) {
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    val currentOnSurface by rememberUpdatedState(onSurface)
    val rotationDegrees = (LocalView.current.display?.rotation ?: Surface.ROTATION_0) * 90
    // Content size in the display's natural orientation.
    val swapped = sensorOrientation % 180 == 90
    val cw = if (swapped) previewSize.height else previewSize.width
    val ch = if (swapped) previewSize.width else previewSize.height
    val displayed = if (rotationDegrees % 180 == 90) ch.toFloat() / cw else cw.toFloat() / ch

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { texture = st }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyPreviewTransform()
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        texture = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> (v as TextureView).applyPreviewTransform() }
            }
        },
        update = { view ->
            view.tag = PreviewGeometry(cw, ch, rotationDegrees)
            view.applyPreviewTransform()
        },
        modifier = Modifier.aspectRatio(displayed, matchHeightConstraintsFirst = true).fillMaxSize(),
    )

    DisposableEffect(texture, previewSize) {
        val st = texture
        if (st != null) {
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            currentOnSurface(Surface(st))
        }
        onDispose { if (st != null) currentOnSurface(null) }
    }
}

private data class PreviewGeometry(val contentWidth: Int, val contentHeight: Int, val rotationDegrees: Int)

private fun TextureView.applyPreviewTransform() {
    val g = tag as? PreviewGeometry ?: return
    val vw = width.toFloat()
    val vh = height.toFloat()
    if (vw <= 0f || vh <= 0f) return
    val cx = vw / 2
    val cy = vh / 2
    val rotated = g.rotationDegrees % 180 == 90
    val rw = if (rotated) g.contentHeight else g.contentWidth
    val rh = if (rotated) g.contentWidth else g.contentHeight
    val scale = min(vw / rw, vh / rh)
    // The view stretches the content to its bounds: undo that, rotate, then fit.
    val m = Matrix()
    m.setScale(g.contentWidth / vw, g.contentHeight / vh, cx, cy)
    m.postRotate(-g.rotationDegrees.toFloat(), cx, cy)
    m.postScale(scale, scale, cx, cy)
    setTransform(m)
}

fun CameraLens.labelRes(): Int = when (this) {
    CameraLens.BACK -> R.string.lens_back
    CameraLens.FRONT -> R.string.lens_front
    CameraLens.EXTERNAL -> R.string.lens_external
}
