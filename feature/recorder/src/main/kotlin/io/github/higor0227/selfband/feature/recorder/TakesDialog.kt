// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.recorder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.higor0227.selfband.core.model.Take
import io.github.higor0227.selfband.core.model.Track
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes of one track (SPEC 2.2): pick the active one; delete the others. The active take is
 * what plays (and later exports); the rest stay saved until deleted.
 */
@Composable
fun TakesDialog(
    trackNumber: Int,
    track: Track,
    sampleRate: Int,
    onSetActive: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<Take?>(null) }
    val activeId = track.activeTake?.id
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.takes_title, trackNumber)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.takes_hint), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    // Newest first.
                    items(track.takes.withIndex().reversed(), key = { it.value.id }) { (i, take) ->
                        val usable = take.lengthFrames > 0
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = usable) { onSetActive(take.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = take.id == activeId, onClick = { onSetActive(take.id) }, enabled = usable)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.take_n, i + 1), style = MaterialTheme.typography.titleSmall)
                                Text(takeSummary(take, sampleRate), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { confirmDelete = take }, enabled = track.takes.size > 1) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.delete_take))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
    confirmDelete?.let { take ->
        val number = track.takes.indexOfFirst { it.id == take.id } + 1
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.delete_take_title, number)) },
            text = { Text(stringResource(R.string.delete_take_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(take.id) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun takeSummary(take: Take, sampleRate: Int): String {
    val parts = mutableListOf<String>()
    parts += if (take.lengthFrames > 0) {
        val sec = take.lengthFrames / sampleRate.coerceAtLeast(1)
        String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
    } else {
        stringResource(R.string.take_incomplete)
    }
    parts += take.video?.let { stringResource(R.string.take_video, stringResource(it.lens.labelRes()), it.height.coerceAtMost(it.width)) }
        ?: stringResource(R.string.audio_only)
    parts += DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(take.createdAtMillis))
    return parts.joinToString(" · ")
}
