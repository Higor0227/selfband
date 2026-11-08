// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.higor0227.selfband.core.model.EqMath
import io.github.higor0227.selfband.core.model.TrackEq
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Track EQ (SPEC 2.5): the response curve on top, then bypass, high-pass, low shelf, the
 * peaking band and high shelf. Changes are heard while dragging and saved on release.
 */
@Composable
fun EqDialog(
    trackNumber: Int,
    initial: TrackEq,
    sampleRate: Int,
    onPreview: (TrackEq) -> Unit,
    onSet: (TrackEq) -> Unit,
    onClose: () -> Unit,
) {
    var eq by remember { mutableStateOf(initial) }
    fun drag(next: TrackEq) { eq = next; onPreview(next) }
    fun commit(next: TrackEq = eq) { eq = next; onSet(next) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.eq_title, trackNumber)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                EqCurve(eq, sampleRate)
                SwitchRow(stringResource(R.string.eq_enabled), eq.enabled) { commit(eq.copy(enabled = it)) }
                HorizontalDivider()

                SwitchRow(stringResource(R.string.eq_high_pass), eq.highPass) { commit(eq.copy(highPass = it)) }
                FreqSlider(eq.highPassHz, TrackEq.MIN_HIGH_PASS_HZ, TrackEq.MAX_HIGH_PASS_HZ, enabled = eq.highPass,
                    onDrag = { drag(eq.copy(highPassHz = it)) }, onDone = { commit() })
                HorizontalDivider()

                Text(stringResource(R.string.eq_low_shelf), style = MaterialTheme.typography.labelLarge)
                FreqSlider(eq.lowShelfHz, TrackEq.MIN_LOW_SHELF_HZ, TrackEq.MAX_LOW_SHELF_HZ,
                    onDrag = { drag(eq.copy(lowShelfHz = it)) }, onDone = { commit() })
                GainSlider(eq.lowShelfDb, onDrag = { drag(eq.copy(lowShelfDb = it)) }, onDone = { commit() },
                    onReset = { commit(eq.copy(lowShelfDb = 0.0)) })
                HorizontalDivider()

                Text(stringResource(R.string.eq_peak), style = MaterialTheme.typography.labelLarge)
                FreqSlider(eq.peakHz, TrackEq.MIN_PEAK_HZ, TrackEq.MAX_PEAK_HZ,
                    onDrag = { drag(eq.copy(peakHz = it)) }, onDone = { commit() })
                GainSlider(eq.peakDb, onDrag = { drag(eq.copy(peakDb = it)) }, onDone = { commit() },
                    onReset = { commit(eq.copy(peakDb = 0.0)) })
                LabeledSlider(
                    label = "Q",
                    value = logPosition(eq.peakQ, TrackEq.MIN_PEAK_Q, TrackEq.MAX_PEAK_Q),
                    text = String.format(Locale.ROOT, "%.2f", eq.peakQ),
                    onDrag = { drag(eq.copy(peakQ = logValue(it, TrackEq.MIN_PEAK_Q, TrackEq.MAX_PEAK_Q))) },
                    onDone = { commit() },
                    onReset = { commit(eq.copy(peakQ = 1.0)) },
                )
                HorizontalDivider()

                Text(stringResource(R.string.eq_high_shelf), style = MaterialTheme.typography.labelLarge)
                FreqSlider(eq.highShelfHz, TrackEq.MIN_HIGH_SHELF_HZ, TrackEq.MAX_HIGH_SHELF_HZ,
                    onDrag = { drag(eq.copy(highShelfHz = it)) }, onDone = { commit() })
                GainSlider(eq.highShelfDb, onDrag = { drag(eq.copy(highShelfDb = it)) }, onDone = { commit() },
                    onReset = { commit(eq.copy(highShelfDb = 0.0)) })
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton(onClick = { commit(TrackEq()) }) { Text(stringResource(R.string.eq_flat)) } },
    )
}

private const val CURVE_RANGE_DB = 18.0

/** Response from 20 Hz to 20 kHz (log), ±18 dB, with 100 Hz / 1 kHz / 10 kHz guides. */
@Composable
private fun EqCurve(eq: TrackEq, sampleRate: Int) {
    val freqs = remember { EqMath.curveFrequencies(96) }
    val response = remember(eq, sampleRate) { freqs.map { EqMath.responseDb(eq, it, sampleRate) } }
    val line = if (eq.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.eq_curve)
    Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = description }) {
        fun x(hz: Double) = (log10(hz / 20.0) / 3.0).toFloat() * size.width
        fun y(db: Double) = ((1 - (db.coerceIn(-CURVE_RANGE_DB, CURVE_RANGE_DB) + CURVE_RANGE_DB) / (2 * CURVE_RANGE_DB)) * size.height).toFloat()
        drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
        for (hz in listOf(100.0, 1000.0, 10_000.0)) {
            drawLine(grid, Offset(x(hz), 0f), Offset(x(hz), size.height), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        freqs.forEachIndexed { i, hz ->
            if (i == 0) path.moveTo(x(hz), y(response[i])) else path.lineTo(x(hz), y(response[i]))
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FreqSlider(hz: Double, min: Double, max: Double, enabled: Boolean = true, onDrag: (Double) -> Unit, onDone: () -> Unit) {
    LabeledSlider(
        label = "Hz",
        value = logPosition(hz, min, max),
        text = formatHz(hz),
        enabled = enabled,
        onDrag = { onDrag(logValue(it, min, max)) },
        onDone = onDone,
    )
}

@Composable
private fun GainSlider(db: Double, onDrag: (Double) -> Unit, onDone: () -> Unit, onReset: () -> Unit) {
    val max = TrackEq.MAX_GAIN_DB
    LabeledSlider(
        label = "dB",
        value = ((db + max) / (2 * max)).toFloat(),
        text = String.format(Locale.ROOT, "%+.1f", db),
        onDrag = {
            val v = it * 2 * max - max
            onDrag(if (abs(v) < 0.4) 0.0 else v) // snap to 0 dB
        },
        onDone = onDone,
        onReset = onReset,
    )
}

/** Slider over 0..1 with a short label on the left and the value (tap to reset) on the right. */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    text: String,
    enabled: Boolean = true,
    onDrag: (Float) -> Unit,
    onDone: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(24.dp))
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onDrag,
            onValueChangeFinished = onDone,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        if (onReset != null) {
            TextButton(onClick = onReset, enabled = enabled) {
                Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Spacer(Modifier.width(12.dp))
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(64.dp))
        }
    }
}

private fun logPosition(v: Double, min: Double, max: Double): Float =
    (ln(v.coerceIn(min, max) / min) / ln(max / min)).toFloat()

private fun logValue(position: Float, min: Double, max: Double): Double =
    min * (max / min).pow(position.coerceIn(0f, 1f).toDouble())

private fun formatHz(hz: Double): String =
    if (hz >= 1000) String.format(Locale.ROOT, "%.1fk", hz / 1000) else String.format(Locale.ROOT, "%.0f", hz)
