// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.layout

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.higor0227.selfband.core.model.Layer
import io.github.higor0227.selfband.core.model.LayerEditing
import io.github.higor0227.selfband.core.video.CanvasPreview
import java.util.Locale
import kotlin.math.abs

@Composable
fun LayoutRoute(onBack: () -> Unit, viewModel: LayoutEditorViewModel = hiltViewModel()) {
    LifecycleStartEffect(Unit) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LayoutEditorScreen(state, viewModel, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutEditorScreen(state: LayoutUiState, vm: LayoutEditorViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.layout_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val project = state.project
            val height = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
            Box(
                Modifier.fillMaxWidth().height(height).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (project != null && state.layers.isNotEmpty()) {
                    Box(Modifier.aspectRatio(state.canvasAspect, matchHeightConstraintsFirst = true)) {
                        CanvasSurface(vm.canvasPreview)
                        EditOverlay(state, vm)
                    }
                } else if (project != null) {
                    Text(
                        stringResource(R.string.layout_no_video),
                        color = Color.White,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            if (project != null && state.layers.isNotEmpty()) {
                Controls(state, vm)
            }
        }
    }
}

/** TextureView the compositor draws into. */
@Composable
private fun CanvasSurface(canvas: CanvasPreview) {
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

/**
 * Touch layer over the canvas: tap selects, drag moves (or pans the video in crop mode),
 * pinch scales (or zooms the video), handles scale or crop. Draws the selection, handles and
 * snap guides.
 */
@Composable
private fun EditOverlay(state: LayoutUiState, vm: LayoutEditorViewModel) {
    val current by rememberUpdatedState(state)
    val colors = MaterialTheme.colorScheme
    Canvas(
        Modifier.fillMaxSize().pointerInput(vm) {
            val handle = 24.dp.toPx()
            val snap = 8.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                var target = vm.hitTest(down.position.x / w, down.position.y / h, handle / w, handle / h)
                var total = Offset.Zero
                var dragging = false
                var pinching = false
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        if (!pinching) {
                            pinching = true
                            dragging = false
                            vm.beginGesture(current.selectedTrackId)
                        }
                        val c = event.calculateCentroid()
                        vm.pinch(event.calculateZoom(), c.x / w, c.y / h)
                    } else if (!pinching) {
                        val change = pressed[0]
                        total += change.position - change.previousPosition
                        if (!dragging && total.getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                            if (target == DragTarget.None) target = vm.grabAt(down.position.x / w, down.position.y / h)
                            vm.beginGesture(target.trackId())
                        }
                        if (dragging) vm.drag(target, total.x / w, total.y / h, snap / w, snap / h)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                if (dragging || pinching) vm.endGesture() else vm.tap(down.position.x / w, down.position.y / h)
            }
        },
    ) {
        val sel = current.selected ?: return@Canvas
        val accent = colors.primary
        val f = sel.frame
        val topLeft = Offset(f.x * size.width, f.y * size.height)
        val frameSize = Size(f.width * size.width, f.height * size.height)
        if (current.mode == EditMode.CROP) {
            // The whole video, beyond the crop window.
            val c = sel.crop
            val vx = f.x - c.x * f.width / c.width
            val vy = f.y - c.y * f.height / c.height
            drawRect(
                Color.White.copy(alpha = 0.7f),
                Offset(vx * size.width, vy * size.height),
                Size(f.width / c.width * size.width, f.height / c.height * size.height),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
            )
        }
        drawRect(
            accent, topLeft, frameSize,
            style = Stroke(
                2.dp.toPx(),
                pathEffect = if (sel.visible) null else PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            ),
        )
        val corners = listOf(
            Offset(topLeft.x, topLeft.y), Offset(topLeft.x + frameSize.width, topLeft.y),
            Offset(topLeft.x + frameSize.width, topLeft.y + frameSize.height), Offset(topLeft.x, topLeft.y + frameSize.height),
        )
        if (current.mode == EditMode.CROP) {
            val mids = listOf(
                Offset(topLeft.x, topLeft.y + frameSize.height / 2), Offset(topLeft.x + frameSize.width, topLeft.y + frameSize.height / 2),
                Offset(topLeft.x + frameSize.width / 2, topLeft.y), Offset(topLeft.x + frameSize.width / 2, topLeft.y + frameSize.height),
            )
            (corners + mids).forEach { squareHandle(it, accent) }
        } else {
            corners.forEach { roundHandle(it, accent) }
        }
        val guideColor = colors.tertiary
        for (g in current.guides) {
            if (g.vertical) {
                val x = g.position * size.width
                drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            } else {
                val y = g.position * size.height
                drawLine(guideColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
        }
    }
}

private fun DragTarget.trackId(): String? = when (this) {
    is DragTarget.Body -> trackId
    is DragTarget.Corner -> trackId
    is DragTarget.CropHandle -> trackId
    DragTarget.None -> null
}

private fun DrawScope.roundHandle(center: Offset, color: Color) {
    drawCircle(Color.White, 8.dp.toPx(), center)
    drawCircle(color, 6.dp.toPx(), center)
}

private fun DrawScope.squareHandle(center: Offset, color: Color) {
    val s = 12.dp.toPx()
    drawRect(Color.White, Offset(center.x - s / 2, center.y - s / 2), Size(s, s))
    drawRect(color, Offset(center.x - s / 2 + 2, center.y - s / 2 + 2), Size(s - 4, s - 4))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(state: LayoutUiState, vm: LayoutEditorViewModel) {
    val project = state.project ?: return
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Playhead: which moment the canvas shows.
        val length = project.lengthFrames
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatTime(state.playheadFrames, project.sampleRate), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = if (length > 0) state.playheadFrames.toFloat() / length else 0f,
                onValueChange = { vm.setPlayhead((it * length).toLong()) },
                enabled = length > 0,
                modifier = Modifier.weight(1f),
            )
        }

        // Layer picker (also reaches hidden layers).
        val trackNumbers = project.tracks.withIndex().associate { it.value.id to it.index + 1 }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            project.tracks.filter { t -> state.layers.any { it.trackId == t.id } }.forEach { track ->
                val layer = state.layers.first { it.trackId == track.id }
                FilterChip(
                    selected = state.selectedTrackId == track.id,
                    onClick = { vm.select(if (state.selectedTrackId == track.id) null else track.id) },
                    label = {
                        Text(
                            stringResource(R.string.layout_track_n, trackNumbers.getValue(track.id)) +
                                if (layer.visible) "" else " " + stringResource(R.string.layout_hidden_suffix),
                        )
                    },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.layout_snap), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.snap, onCheckedChange = vm::setSnap)
        }
        if (state.canvas?.templateId != null) {
            Text(stringResource(R.string.layout_template_note), style = MaterialTheme.typography.bodySmall)
        }

        val sel = state.selected
        if (sel == null) {
            Text(stringResource(R.string.layout_hint_select), style = MaterialTheme.typography.bodyMedium)
        } else {
            SelectedLayerControls(sel, state, vm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedLayerControls(sel: Layer, state: LayoutUiState, vm: LayoutEditorViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = state.mode == EditMode.MOVE,
            onClick = { vm.setMode(EditMode.MOVE) },
            label = { Text(stringResource(R.string.layout_mode_move)) },
        )
        FilterChip(
            selected = state.mode == EditMode.CROP,
            onClick = { vm.setMode(EditMode.CROP) },
            label = { Text(stringResource(R.string.layout_mode_crop)) },
        )
    }
    Text(
        stringResource(if (state.mode == EditMode.CROP) R.string.layout_hint_crop else R.string.layout_hint_move),
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.mode == EditMode.CROP) {
        OutlinedButton(onClick = vm::resetCrop) { Text(stringResource(R.string.layout_reset_crop)) }
    }

    Text(stringResource(R.string.layout_align), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            LayerEditing.Align.LEFT to R.string.align_left,
            LayerEditing.Align.CENTER_H to R.string.align_center_h,
            LayerEditing.Align.RIGHT to R.string.align_right,
            LayerEditing.Align.TOP to R.string.align_top,
            LayerEditing.Align.CENTER_V to R.string.align_center_v,
            LayerEditing.Align.BOTTOM to R.string.align_bottom,
        ).forEach { (align, label) ->
            OutlinedButton(onClick = { vm.align(align) }) { Text(stringResource(label)) }
        }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { vm.restack(up = true) }) { Text(stringResource(R.string.layout_bring_forward)) }
        OutlinedButton(onClick = { vm.restack(up = false) }) { Text(stringResource(R.string.layout_send_backward)) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.layout_visible), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = sel.visible, onCheckedChange = { on -> vm.editSelected { it.copy(visible = on) } })
    }

    LabeledSlider(R.string.layout_opacity, sel.opacity, 0.1f..1f, percent(sel.opacity),
        onChange = { v -> vm.editSelected(save = false) { it.copy(opacity = v) } }, onDone = { vm.editSelected { it } })
    LabeledSlider(R.string.layout_corners, sel.cornerRadius, 0f..0.5f, percent(sel.cornerRadius * 2f),
        onChange = { v -> vm.editSelected(save = false) { it.copy(cornerRadius = v) } }, onDone = { vm.editSelected { it } })
    LabeledSlider(R.string.layout_border, sel.borderWidth, 0f..MAX_BORDER, percent(sel.borderWidth / MAX_BORDER),
        onChange = { v -> vm.editSelected(save = false) { it.copy(borderWidth = v) } }, onDone = { vm.editSelected { it } })
    if (sel.borderWidth > 0f) {
        Text(stringResource(R.string.layout_border_color), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BORDER_COLORS.forEach { argb ->
                val selected = sel.borderColor == argb
                Box(
                    Modifier.size(32.dp)
                        .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .padding(4.dp)
                        .background(Color(argb.toInt()), CircleShape)
                        .clickable { vm.editSelected { it.copy(borderColor = argb) } },
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun LabeledSlider(
    label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.35f))
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.weight(0.5f),
        )
        Text(valueText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.15f))
    }
}

private fun percent(v: Float) = "${(v * 100).toInt()}%"

/** Border thickness at the end of the slider: 3% of the canvas's shorter side. */
private const val MAX_BORDER = 0.03f

private val BORDER_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFFF6F00,
)

/** m:ss.mmm */
private fun formatTime(frames: Long, sampleRate: Int): String {
    val totalMs = abs(frames) * 1000 / sampleRate.coerceAtLeast(1)
    return String.format(Locale.ROOT, "%d:%02d.%03d", totalMs / 60_000, (totalMs / 1000) % 60, totalMs % 1000)
}
