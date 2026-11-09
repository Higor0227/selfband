// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.higor0227.selfband.core.model.LayoutTemplate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.higor0227.selfband.core.model.AspectRatio
import io.github.higor0227.selfband.core.model.CanvasLayout
import io.github.higor0227.selfband.core.model.LayoutTemplates
import io.github.higor0227.selfband.core.model.NormRect
import io.github.higor0227.selfband.core.model.value

/** Callbacks for the canvas card. */
data class CanvasActions(
    val onAspectRatio: (AspectRatio) -> Unit,
    val onTemplate: (String) -> Unit,
    val onEditLayout: () -> Unit,
    /** A template saved by the user: applied as a free layout. */
    val onUserTemplate: (String) -> Unit = {},
    val onRenameTemplate: (id: String, name: String) -> Unit = { _, _ -> },
    val onDeleteTemplate: (String) -> Unit = {},
)

/** Canvas format and layout template (SPEC 2.6). Shown while the panel shows the canvas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CanvasLayoutCard(state: RecorderUiState, actions: CanvasActions) {
    val project = state.project ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.canvas_settings), style = MaterialTheme.typography.titleSmall)

            Text(stringResource(R.string.canvas_format), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AspectRatio.entries.forEach { ratio ->
                    FilterChip(
                        selected = project.aspectRatio == ratio,
                        onClick = { actions.onAspectRatio(ratio) },
                        label = { Text(ratio.label()) },
                    )
                }
            }

            Text(stringResource(R.string.canvas_layout), style = MaterialTheme.typography.labelLarge)
            // null = free layout (edited in the layout editor): no template is selected.
            val current = project.canvas.templateId
            val autoSlots = CanvasLayout.slots(project.copy(canvas = project.canvas.copy(templateId = LayoutTemplates.AUTO)))
                .orEmpty()
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TemplateThumb(
                    label = stringResource(R.string.layout_auto),
                    description = stringResource(R.string.layout_auto),
                    slots = autoSlots,
                    aspect = project.aspectRatio.value,
                    selected = current != null && (current == LayoutTemplates.AUTO ||
                        LayoutTemplates.builtIn.none { it.id == current }),
                    onClick = { actions.onTemplate(LayoutTemplates.AUTO) },
                )
                LayoutTemplates.builtIn.forEach { template ->
                    val slots = template.slotsFor(project.aspectRatio)
                    TemplateThumb(
                        label = slots.size.toString(),
                        description = stringResource(R.string.layout_slots, slots.size),
                        slots = slots,
                        aspect = project.aspectRatio.value,
                        selected = current == template.id,
                        onClick = { actions.onTemplate(template.id) },
                    )
                }
            }
            if (state.userTemplates.isNotEmpty()) {
                var managing by remember { mutableStateOf<LayoutTemplate?>(null) }
                Text(stringResource(R.string.canvas_my_templates), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.userTemplates.forEach { template ->
                        val name = template.name.orEmpty()
                        TemplateThumb(
                            label = if (name.length > THUMB_NAME_CHARS) name.take(THUMB_NAME_CHARS - 1) + "…" else name,
                            description = stringResource(R.string.canvas_user_template, name),
                            slots = template.slotsFor(project.aspectRatio),
                            aspect = project.aspectRatio.value,
                            selected = false,
                            onClick = { actions.onUserTemplate(template.id) },
                            onLongClick = { managing = template },
                        )
                    }
                }
                Text(stringResource(R.string.canvas_my_templates_hint), style = MaterialTheme.typography.bodySmall)
                managing?.let { template ->
                    ManageTemplateDialog(
                        template = template,
                        onRename = { name -> actions.onRenameTemplate(template.id, name); managing = null },
                        onDelete = { actions.onDeleteTemplate(template.id); managing = null },
                        onDismiss = { managing = null },
                    )
                }
            }
            val hidden = CanvasLayout.hiddenByTemplate(project)
            if (hidden > 0) {
                Text(
                    stringResource(R.string.canvas_hidden_tracks, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (current == null) {
                Text(
                    stringResource(R.string.layout_free),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(stringResource(R.string.canvas_layout_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = actions.onEditLayout) { Text(stringResource(R.string.edit_layout)) }
        }
    }
}

/** Miniature of a layout: its slots drawn on a canvas of the project's shape. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateThumb(
    label: String,
    description: String,
    slots: List<NormRect>,
    aspect: Float,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val outline = if (selected) colors.primary else colors.outline
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = description },
    ) {
        Box(
            Modifier.height(THUMB_HEIGHT).aspectRatio(aspect)
                .border(if (selected) 2.dp else 1.dp, outline, RoundedCornerShape(2.dp)),
        ) {
            Canvas(Modifier.matchParentSize().padding(3.dp)) {
                val fill = if (selected) colors.primary.copy(alpha = 0.35f) else colors.onSurface.copy(alpha = 0.15f)
                val gap = 1.dp.toPx()
                for (r in slots) {
                    val topLeft = Offset(r.x * size.width + gap, r.y * size.height + gap)
                    val s = Size(r.width * size.width - 2 * gap, r.height * size.height - 2 * gap)
                    if (s.width <= 0f || s.height <= 0f) continue
                    drawRect(fill, topLeft, s)
                    drawRect(outline, topLeft, s, style = Stroke(width = 1.dp.toPx()))
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) colors.primary else colors.onSurface)
    }
}

private val THUMB_HEIGHT = 44.dp
private const val THUMB_NAME_CHARS = 10

/** Rename or delete a saved template. Projects that used it keep their layout either way. */
@Composable
private fun ManageTemplateDialog(
    template: LayoutTemplate,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(template.id) { mutableStateOf(template.name.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.canvas_delete_template_title, template.name.orEmpty())) },
            text = { Text(stringResource(R.string.canvas_delete_template_text)) },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(R.string.canvas_delete_template)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canvas_template_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(LayoutTemplates.MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.canvas_template_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.canvas_template_rename)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.canvas_delete_template), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

private fun AspectRatio.label(): String = when (this) {
    AspectRatio.PORTRAIT_9_16 -> "9:16"
    AspectRatio.LANDSCAPE_16_9 -> "16:9"
    AspectRatio.SQUARE_1_1 -> "1:1"
}
