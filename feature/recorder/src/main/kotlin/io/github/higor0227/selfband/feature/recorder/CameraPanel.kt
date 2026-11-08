// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.higor0227.selfband.core.camera.PixelSize
import io.github.higor0227.selfband.core.model.CameraLens
import io.github.higor0227.selfband.core.model.value
import io.github.higor0227.selfband.core.video.CanvasPreview
import kotlin.math.min

/**
 * Panel above the track list: the camera preview (with grid and ghost) and the main video
 * switches, or the live canvas (SPEC 2.6) with a playhead. The camera keeps running under the
 * canvas so switching back is instant and the capture session is never rebuilt.
 */
@Composable
fun CameraPanel(
    state: RecorderUiState,
    ghostOpacity: Float,
    canvas: CanvasPreview,
    onPreviewSurface: (Surface?) -> Unit,
    onSwitchLens: () -> Unit,
    onVideoEnabled: (Boolean) -> Unit,
    onPanelMode: (PanelMode) -> Unit,
    onPlayhead: (Long) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val height = (configuration.screenHeightDp * if (landscape) 0.45f else 0.32f).dp
    val cam = state.camera
    val settings = state.cameraSettings
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(height).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // While the camera reopens (lens or format change) keep the view and its surface,
            // so the preview does not flash and the session can reuse the surface.
            var lastGeometry by remember { mutableStateOf<Pair<PixelSize, Int>?>(null) }
            val openSize = cam.previewSize
            if (cam.isOpen && openSize != null) lastGeometry = openSize to cam.sensorOrientation
            val geometry = lastGeometry
            val error = cam.error
            when {
                !state.cameraPermission -> PanelMessage(stringResource(R.string.camera_permission_needed))
                !state.videoEnabled -> PanelMessage(stringResource(R.string.video_off))
                error != null && !cam.isOpen -> PanelMessage(stringResource(R.string.camera_error, error))
                geometry == null -> PanelMessage(stringResource(R.string.camera_opening))
                else -> CameraPreview(
                    previewSize = geometry.first,
                    sensorOrientation = geometry.second,
                    grid = settings.grid,
                    ghost = state.ghost,
                    ghostOpacity = ghostOpacity,
                    onSurface = onPreviewSurface,
                )
            }
            if (state.panelMode == PanelMode.CANVAS) {
                Box(Modifier.matchParentSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    val project = state.project
                    if (project != null && project.tracks.any { it.activeTake?.video != null }) {
                        CanvasSurface(canvas, project.aspectRatio.value)
                    } else {
                        PanelMessage(stringResource(R.string.canvas_no_video))
                    }
                }
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
            FilterChip(
                selected = state.panelMode == PanelMode.CAMERA,
                onClick = { onPanelMode(PanelMode.CAMERA) },
                label = { Text(stringResource(R.string.panel_camera)) },
            )
            FilterChip(
                selected = state.panelMode == PanelMode.CANVAS,
                onClick = { onPanelMode(PanelMode.CANVAS) },
                label = { Text(stringResource(R.string.panel_canvas)) },
            )
        }
        if (state.panelMode == PanelMode.CANVAS) {
            PlayheadRow(state, onPlayhead)
        } else Row(
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
                    (size?.let { "${it.height}p · " } ?: "") + "${cam.frameRate} fps",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(
                onClick = onSwitchLens,
                enabled = state.isIdle && state.videoEnabled && state.availableLenses.size > 1,
            ) {
                Text(stringResource(R.string.switch_camera, stringResource(settings.lens.labelRes())))
            }
        }
    }
}

/** Playhead: where playback starts and which frame the canvas shows while stopped. */
@Composable
private fun PlayheadRow(state: RecorderUiState, onPlayhead: (Long) -> Unit) {
    val project = state.project ?: return
    val length = project.lengthFrames
    val position = if (state.isIdle) state.playheadFrames else state.status.positionFrames
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(formatTime(position.coerceAtLeast(0), project.sampleRate), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f,
            onValueChange = { onPlayhead((it * length).toLong()) },
            enabled = state.isIdle && length > 0,
            modifier = Modifier.weight(1f),
        )
        Text(formatTime(length, project.sampleRate), style = MaterialTheme.typography.labelMedium)
    }
}

/** TextureView the canvas compositor draws into, at the project's aspect ratio. */
@Composable
private fun CanvasSurface(canvas: CanvasPreview, aspect: Float) {
    Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surface: Surface? = null
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            surface = Surface(st).also { canvas.attach(it, w, h) }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = canvas.resize(w, h)
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            canvas.detach()
                            surface?.release()
                            surface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
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
 * rotation and keeps the aspect ratio. Grid and ghost are drawn over the same area.
 */
@Composable
private fun CameraPreview(
    previewSize: PixelSize,
    sensorOrientation: Int,
    grid: Boolean,
    ghost: GhostUi?,
    ghostOpacity: Float,
    onSurface: (Surface?) -> Unit,
) {
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    val currentOnSurface by rememberUpdatedState(onSurface)
    val rotationDegrees = (LocalView.current.display?.rotation ?: Surface.ROTATION_0) * 90
    // Content size in the display's natural orientation.
    val swapped = sensorOrientation % 180 == 90
    val cw = if (swapped) previewSize.height else previewSize.width
    val ch = if (swapped) previewSize.width else previewSize.height
    val displayed = if (rotationDegrees % 180 == 90) ch.toFloat() / cw else cw.toFloat() / ch

    Box(Modifier.aspectRatio(displayed, matchHeightConstraintsFirst = true)) {
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
            modifier = Modifier.fillMaxSize(),
        )
        if (ghost != null) {
            Image(
                ghost.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = ghostOpacity
                    // Shown the way that track looked in its own preview.
                    scaleX = if (ghost.mirrored) -1f else 1f
                },
            )
        }
        if (grid) GridOverlay()
    }

    DisposableEffect(texture, previewSize) {
        val st = texture
        if (st != null) {
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            currentOnSurface(Surface(st))
        }
        onDispose { if (st != null) currentOnSurface(null) }
    }
}

/** Rule-of-thirds framing grid. */
@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.5f)
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3
            val y = size.height * i / 3
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
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
