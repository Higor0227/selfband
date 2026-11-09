// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.github.higor0227.selfband.core.model.AlignmentMath
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.model.Take
import io.github.higor0227.selfband.core.model.Track
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Callbacks for the timeline card. */
data class TimelineActions(
    /** New manual offset (frames) of the track's active take. */
    val onSetOffsetFrames: (String, Long) -> Unit,
    val onSetTrim: (Long, Long) -> Unit,
    val onClearTrim: () -> Unit,
    val onPlayhead: (Long) -> Unit,
)

private class Lane(val number: Int, val track: Track, val take: Take)

/** What a horizontal drag on the timeline is doing. */
private enum class DragKind { NONE, PAN, TRACK, TRIM_START, TRIM_END }

private const val MIN_FRAMES_PER_PX = 2f

/**
 * Timeline (SPEC 2.5 / 2.6): one waveform lane per track on a shared time axis.
 * - Drag a lane sideways to move that track (its manual offset); zoom in for precision and
 *   use the nudge buttons for single samples.
 * - Drag the handles on the ruler to set the global trim; the region outside is dimmed.
 * - Tap the ruler to move the playhead; drag elsewhere on the ruler to scroll when zoomed.
 * Offsets and the trim are saved on release and never rewrite the audio files.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelineCard(state: RecorderUiState, actions: TimelineActions) {
    val project = state.project ?: return
    val lanes = remember(project.tracks) {
        project.tracks.mapIndexedNotNull { i, t ->
            t.activeTake?.takeIf { it.lengthFrames > 0 }?.let { Lane(i + 1, t, it) }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lanes.isEmpty()) {
                Text(stringResource(R.string.timeline), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.no_tracks), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            TimelineBody(state, project, lanes, actions)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineBody(state: RecorderUiState, project: Project, lanes: List<Lane>, actions: TimelineActions) {
    val sr = project.sampleRate
    val canEdit = state.isIdle
    val contentStart = min(0L, lanes.minOf { it.take.startFrame })
    val contentEnd = max(project.lengthFrames, contentStart + sr / 10)

    var widthPx by remember { mutableIntStateOf(0) }
    var zoomFpp by remember { mutableFloatStateOf(0f) } // frames per pixel; 0 = fit everything
    var viewStart by remember { mutableDoubleStateOf(contentStart.toDouble()) }
    val fitFpp = (contentEnd - contentStart).toFloat() / max(widthPx, 1)
    val fpp = if (zoomFpp <= 0f || zoomFpp >= fitFpp) fitFpp else zoomFpp
    fun clampView(start: Double, f: Float): Double {
        val maxStart = contentEnd - widthPx * f.toDouble()
        return if (maxStart <= contentStart) contentStart.toDouble() else start.coerceIn(contentStart.toDouble(), maxStart)
    }
    val view = clampView(viewStart, fpp)

    var selectedId by remember { mutableStateOf(lanes.first().track.id) }
    val selected = lanes.firstOrNull { it.track.id == selectedId } ?: lanes.first()

    // Drag state. A moved lane keeps its drawn offset until the saved project comes back.
    var dragKind by remember { mutableStateOf(DragKind.NONE) }
    var movingId by remember { mutableStateOf<String?>(null) }
    var movePx by remember { mutableFloatStateOf(0f) }
    var trimStartDraft by remember { mutableLongStateOf(-1L) }
    var trimEndDraft by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(project.tracks) { if (dragKind != DragKind.TRACK) { movingId = null; movePx = 0f } }
    LaunchedEffect(movingId, dragKind) {
        // Fallback when the save was refused (e.g. playback started): drop the ghost offset.
        if (movingId != null && dragKind == DragKind.NONE) { delay(1500); movingId = null; movePx = 0f }
    }
    LaunchedEffect(project.trimStartFrame, project.trimEndFrame) { trimStartDraft = -1L; trimEndDraft = -1L }
    val trimStart = if (trimStartDraft >= 0) trimStartDraft else project.trimStart
    val trimEnd = if (trimEndDraft >= 0) trimEndDraft else project.trimEnd
    val live = LocalLiveStatus.current
    val idle = state.isIdle
    val playhead = state.playheadFrames

    val density = LocalDensity.current
    val rulerPx = with(density) { 28.dp.toPx() }
    val lanePx = with(density) { 44.dp.toPx() }
    val gapPx = with(density) { 4.dp.toPx() }
    val handleHitPx = with(density) { 24.dp.toPx() }
    val heightDp = with(density) { (rulerPx + lanes.size * (lanePx + gapPx)).toDp() }

    // Gesture handlers run in long-lived coroutines: read the latest values through these.
    val geo by rememberUpdatedState(
        Geo(view, fpp, lanes, canEdit, trimStart, trimEnd, rulerPx, lanePx, gapPx, handleHitPx, contentStart, contentEnd, widthPx, project.lengthFrames),
    )
    val currentActions by rememberUpdatedState(actions)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.timeline), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            val center = view + widthPx * fpp / 2.0
            val next = max(MIN_FRAMES_PER_PX, fpp / 2f)
            zoomFpp = next
            viewStart = clampView(center - widthPx * next / 2.0, next)
        }, enabled = fpp > MIN_FRAMES_PER_PX) { Text(stringResource(R.string.zoom_in)) }
        TextButton(onClick = {
            val center = view + widthPx * fpp / 2.0
            val next = fpp * 2f
            if (next >= fitFpp) zoomFpp = 0f else {
                zoomFpp = next
                viewStart = clampView(center - widthPx * next / 2.0, next)
            }
        }, enabled = fpp < fitFpp) { Text(stringResource(R.string.zoom_out)) }
        TextButton(onClick = { zoomFpp = 0f }, enabled = fpp < fitFpp) { Text(stringResource(R.string.zoom_fit)) }
    }

    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val description = stringResource(R.string.timeline_description)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(heightDp)
            .onSizeChanged { widthPx = it.width }
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val g = geo
                    if (offset.y < g.rulerPx) {
                        if (g.canEdit) currentActions.onPlayhead(g.frameAt(offset.x).coerceIn(0L, g.length))
                    } else {
                        g.laneAt(offset.y)?.let { selectedId = it.track.id }
                    }
                }
            }
            .pointerInput(Unit) {
                var x = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val g = geo
                        x = offset.x
                        dragKind = when {
                            offset.y < g.rulerPx && g.canEdit -> {
                                val ds = abs(offset.x - g.xOf(g.trimStart))
                                val de = abs(offset.x - g.xOf(g.trimEnd))
                                when {
                                    min(ds, de) > g.handleHitPx -> DragKind.PAN
                                    ds < de -> DragKind.TRIM_START
                                    else -> DragKind.TRIM_END
                                }
                            }
                            offset.y >= g.rulerPx && g.canEdit -> {
                                val lane = g.laneAt(offset.y)
                                if (lane != null) {
                                    selectedId = lane.track.id
                                    movingId = lane.track.id
                                    movePx = 0f
                                    DragKind.TRACK
                                } else DragKind.PAN
                            }
                            else -> DragKind.PAN
                        }
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val g = geo
                        x += dx
                        when (dragKind) {
                            DragKind.PAN -> viewStart = g.clampView(viewStart - dx * g.fpp)
                            DragKind.TRACK -> movePx += dx
                            DragKind.TRIM_START -> {
                                val f = g.frameAt(x).coerceIn(0L, g.length)
                                trimStartDraft = f
                                currentActions.onPlayhead(f)
                            }
                            DragKind.TRIM_END -> {
                                val f = g.frameAt(x).coerceIn(0L, g.length)
                                trimEndDraft = f
                                currentActions.onPlayhead(f)
                            }
                            DragKind.NONE -> Unit
                        }
                    },
                    onDragEnd = {
                        val g = geo
                        when (dragKind) {
                            DragKind.TRACK -> {
                                val lane = g.lanes.firstOrNull { it.track.id == movingId }
                                val delta = (movePx * g.fpp).roundToLong()
                                if (lane != null && delta != 0L) {
                                    currentActions.onSetOffsetFrames(lane.track.id, lane.take.manualOffsetFrames + delta)
                                } else { movingId = null; movePx = 0f }
                            }
                            DragKind.TRIM_START, DragKind.TRIM_END -> currentActions.onSetTrim(g.trimStart, g.trimEnd)
                            else -> Unit
                        }
                        dragKind = DragKind.NONE
                    },
                    onDragCancel = {
                        dragKind = DragKind.NONE
                        movingId = null
                        movePx = 0f
                        trimStartDraft = -1L
                        trimEndDraft = -1L
                    },
                )
            },
    ) {
        val g = Geo(view, fpp, lanes, canEdit, trimStart, trimEnd, rulerPx, lanePx, gapPx, handleHitPx, contentStart, contentEnd, widthPx, project.lengthFrames)
        val w = size.width

        // Ruler: time labels at a readable spacing.
        val labelSpacingPx = 90.dp.toPx()
        val step = niceStepFrames(labelSpacingPx * fpp, sr)
        var t = (view / step).toLong() * step
        while (g.xOf(t) <= w) {
            val x = g.xOf(t)
            if (x >= 0) {
                drawLine(colors.outline, Offset(x, rulerPx - 8.dp.toPx()), Offset(x, rulerPx), strokeWidth = 1.dp.toPx())
                drawText(textMeasurer, formatTime(t.coerceAtLeast(0), sr), Offset(x + 2.dp.toPx(), 0f), labelStyle)
            }
            t += step
        }

        // Lanes.
        lanes.forEachIndexed { i, lane ->
            val top = rulerPx + i * (lanePx + gapPx)
            val mid = top + lanePx / 2
            drawRoundRect(colors.surfaceVariant, Offset(0f, top), Size(w, lanePx), CornerRadius(6.dp.toPx()))
            val shift = if (lane.track.id == movingId) (movePx * fpp).roundToLong() else 0L
            val start = lane.take.startFrame + shift
            val x0 = g.xOf(start).coerceAtLeast(0f)
            val x1 = g.xOf(start + lane.take.lengthFrames).coerceAtMost(w)
            if (x1 > x0) {
                val isSelected = lane.track.id == selected.track.id
                drawRect(colors.primary.copy(alpha = if (isSelected) 0.22f else 0.12f), Offset(x0, top), Size(x1 - x0, lanePx))
                val waveform = state.waveforms[lane.take.id]
                if (waveform != null) {
                    val path = Path()
                    var x = x0
                    val half = lanePx / 2 - 2.dp.toPx()
                    while (x < x1) {
                        val f0 = (view + x * fpp).toLong() - start
                        val p = waveform.peak(f0, f0 + max(1L, fpp.toLong()))
                        val h = displayLevel(p) * half
                        if (h > 0.5f) {
                            path.moveTo(x, mid - h)
                            path.lineTo(x, mid + h)
                        }
                        x += 1f
                    }
                    drawPath(path, if (lane.track.muted) colors.outline else colors.primary, style = Stroke(width = 1f))
                }
                if (isSelected) {
                    drawRoundRect(colors.primary, Offset(x0, top), Size(x1 - x0, lanePx), CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx()))
                }
            }
            drawText(textMeasurer, "${lane.number}", Offset(4.dp.toPx(), top + 2.dp.toPx()), labelStyle)
        }

        // Global trim: dim what is cut, mark the handles on the ruler.
        val scrim = Color.Black.copy(alpha = 0.4f)
        val xs = g.xOf(trimStart)
        val xe = g.xOf(trimEnd)
        if (xs > 0) drawRect(scrim, Offset(0f, 0f), Size(min(xs, w), size.height))
        if (xe < w) drawRect(scrim, Offset(max(xe, 0f), 0f), Size(w - max(xe, 0f), size.height))
        val handle = Path()
        for (hx in listOf(xs, xe)) {
            if (hx < -handleHitPx || hx > w + handleHitPx) continue
            drawLine(colors.tertiary, Offset(hx, 0f), Offset(hx, size.height), strokeWidth = 2.dp.toPx())
            handle.moveTo(hx - 8.dp.toPx(), 0f)
            handle.lineTo(hx + 8.dp.toPx(), 0f)
            handle.lineTo(hx, 12.dp.toPx())
            handle.close()
        }
        drawPath(handle, colors.tertiary)

        // Playhead, read here so playback only redraws the timeline.
        val position = if (idle) playhead else live.value.positionFrames
        val px = g.xOf(position)
        if (px in 0f..w) drawLine(colors.error, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx())
    }

    // Selected track: sample-accurate nudges.
    val manual = selected.take.manualOffsetFrames
    Text(
        stringResource(
            R.string.timeline_offset, selected.number,
            String.format(Locale.ROOT, "%+.2f", AlignmentMath.framesToMs(manual.toDouble(), sr)), manual,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    val ms = AlignmentMath.msToFrames(1.0, sr)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            -10 * ms to "−10 ms", -ms to "−1 ms", -1L to stringResource(R.string.nudge_minus_sample),
            1L to stringResource(R.string.nudge_plus_sample), ms to "+1 ms", 10 * ms to "+10 ms",
        ).forEach { (delta, label) ->
            OutlinedButton(
                onClick = { actions.onSetOffsetFrames(selected.track.id, manual + delta) },
                enabled = canEdit,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(label, style = MaterialTheme.typography.labelMedium) }
        }
        TextButton(onClick = { actions.onSetOffsetFrames(selected.track.id, 0L) }, enabled = canEdit && manual != 0L) {
            Text(stringResource(R.string.nudge_reset))
        }
    }

    // Global trim.
    Text(
        if (project.isTrimmed) stringResource(
            R.string.trim_value, formatTime(project.trimStart, sr), formatTime(project.trimEnd, sr),
            formatTime(project.trimEnd - project.trimStart, sr),
        ) else stringResource(R.string.trim_none),
        style = MaterialTheme.typography.bodySmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { actions.onSetTrim(state.playheadFrames, project.trimEnd) }, enabled = canEdit) {
            Text(stringResource(R.string.trim_start_here))
        }
        TextButton(onClick = { actions.onSetTrim(project.trimStart, state.playheadFrames) }, enabled = canEdit) {
            Text(stringResource(R.string.trim_end_here))
        }
        TextButton(onClick = actions.onClearTrim, enabled = canEdit && project.isTrimmed) {
            Text(stringResource(R.string.trim_clear))
        }
    }
    Text(stringResource(R.string.timeline_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Timeline geometry: frames ↔ pixels and lane hit testing. */
private class Geo(
    val view: Double,
    val fpp: Float,
    val lanes: List<Lane>,
    val canEdit: Boolean,
    val trimStart: Long,
    val trimEnd: Long,
    val rulerPx: Float,
    val lanePx: Float,
    val gapPx: Float,
    val handleHitPx: Float,
    val contentStart: Long,
    val contentEnd: Long,
    val widthPx: Int,
    val length: Long,
) {
    /** Keeps the view inside the content (same rule as the composable's clampView). */
    fun clampView(start: Double): Double {
        val maxStart = contentEnd - widthPx * fpp.toDouble()
        return if (maxStart <= contentStart) contentStart.toDouble() else start.coerceIn(contentStart.toDouble(), maxStart)
    }

    fun xOf(frame: Long): Float = ((frame - view) / fpp).toFloat()
    fun frameAt(x: Float): Long = (view + x * fpp).roundToLong()
    fun laneAt(y: Float): Lane? {
        if (y < rulerPx) return null
        val i = ((y - rulerPx) / (lanePx + gapPx)).toInt()
        return lanes.getOrNull(i)
    }
}

/** Peak (linear) to a 0..1 bar height on a −48..0 dBFS scale, so quiet takes stay visible. */
private fun displayLevel(peak: Float): Float =
    if (peak <= 0f) 0f else ((20f * log10(peak) + 48f) / 48f).coerceIn(0f, 1f)

/** A round label step (in frames) of at least [minFrames]. */
private fun niceStepFrames(minFrames: Float, sampleRate: Int): Long {
    val steps = doubleArrayOf(0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0)
    val seconds = steps.firstOrNull { it * sampleRate >= minFrames } ?: steps.last()
    return max(1L, (seconds * sampleRate).roundToLong())
}
