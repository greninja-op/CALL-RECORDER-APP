package com.personal.unifiedrecorder.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.personal.unifiedrecorder.R
import com.personal.unifiedrecorder.core.data.DashboardFilter
import com.personal.unifiedrecorder.core.data.DurationFormat
import com.personal.unifiedrecorder.core.model.CallTypeFilter
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.DirectionFilter
import com.personal.unifiedrecorder.core.playback.PlaybackState
import com.personal.unifiedrecorder.core.playback.PlaybackStatus
import com.personal.unifiedrecorder.ui.DashboardCallbacks
import com.personal.unifiedrecorder.ui.DashboardUiState
import com.personal.unifiedrecorder.ui.theme.UnifiedRecorderTheme

/**
 * Dashboard screen (Req 5 / 7). Lists recordings newest-first via the core
 * `DashboardFilter`, exposes Call_Type and Direction segmented filters plus a
 * search field, renders each row (name / number / date-time / direction /
 * duration), a playback transport driven by the core `PlaybackStateModel`
 * state, a timestamped bookmark overlay, a per-filter empty state, and a
 * playback error slot. Stateless: intents are hoisted through [callbacks].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    callbacks: DashboardCallbacks,
    modifier: Modifier = Modifier
) {
    val result = DashboardFilter.apply(state.allEntries, state.filterState)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dashboard_title)) },
                actions = {
                    TextButton(onClick = callbacks.onShowConsent) {
                        Text(stringResource(R.string.dashboard_show_consent))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.filterState.searchText,
                onValueChange = callbacks.onSearchChange,
                label = { Text(stringResource(R.string.dashboard_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            CallTypeFilterRow(
                selected = state.filterState.callTypeFilter,
                onSelected = callbacks.onCallTypeFilterChange
            )
            Spacer(Modifier.height(4.dp))
            DirectionFilterRow(
                selected = state.filterState.directionFilter,
                onSelected = callbacks.onDirectionFilterChange
            )
            Spacer(Modifier.height(8.dp))

            if (result.isEmpty) {
                EmptyState(filterActive = isFilterActive(state))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(result.entries, key = { it.fileName }) { entry ->
                        RecordingRowCard(
                            row = RecordingDisplay.toRow(entry),
                            playback = state.playback,
                            callbacks = callbacks
                        )
                    }
                }
            }
        }
    }

    // Timestamped bookmark overlay (Req 7.8), anchored to the current playback
    // position of the targeted recording.
    state.bookmarkTarget?.let { target ->
        BookmarkDialog(
            positionMillis = state.playback.positionMillis,
            onDismiss = callbacks.onDismissBookmark,
            onSave = { text -> callbacks.onSaveBookmark(target, state.playback.positionMillis, text) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallTypeFilterRow(
    selected: CallTypeFilter,
    onSelected: (CallTypeFilter) -> Unit
) {
    val options = listOf(
        CallTypeFilter.ALL to R.string.filter_all,
        CallTypeFilter.WHATSAPP to R.string.filter_whatsapp,
        CallTypeFilter.CELLULAR to R.string.filter_cellular
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, labelRes) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelected(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectionFilterRow(
    selected: DirectionFilter,
    onSelected: (DirectionFilter) -> Unit
) {
    val options = listOf(
        DirectionFilter.ALL to R.string.filter_all,
        DirectionFilter.INCOMING to R.string.filter_incoming,
        DirectionFilter.OUTGOING to R.string.filter_outgoing
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, labelRes) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelected(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@Composable
private fun RecordingRowCard(
    row: RecordingRow,
    playback: PlaybackState,
    callbacks: DashboardCallbacks
) {
    val isBound = playback.currentEntry == row.fileName
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = directionSymbol(row.direction),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = row.durationLabel ?: stringResource(R.string.dashboard_duration_unknown),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = row.number ?: stringResource(R.string.dashboard_unknown_number),
                style = MaterialTheme.typography.bodyMedium
            )
            row.dateTimeLabel?.let { label ->
                Text(text = label, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
            PlaybackTransport(
                fileName = row.fileName,
                isBound = isBound,
                status = playback.status,
                callbacks = callbacks
            )

            // Per-entry playback error slot (Req 7.10): shown for the bound entry
            // that failed; the recording itself remains in the list.
            if (isBound && playback.status == PlaybackStatus.ERROR) {
                Text(
                    text = playback.errorMessage ?: stringResource(R.string.playback_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PlaybackTransport(
    fileName: String,
    isBound: Boolean,
    status: PlaybackStatus,
    callbacks: DashboardCallbacks
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            isBound && status == PlaybackStatus.PLAYING -> {
                OutlinedButton(onClick = callbacks.onPause) {
                    Text(stringResource(R.string.playback_pause))
                }
                OutlinedButton(onClick = callbacks.onStop) {
                    Text(stringResource(R.string.playback_stop))
                }
            }

            isBound && status == PlaybackStatus.PAUSED -> {
                OutlinedButton(onClick = callbacks.onResume) {
                    Text(stringResource(R.string.playback_resume))
                }
                OutlinedButton(onClick = callbacks.onStop) {
                    Text(stringResource(R.string.playback_stop))
                }
            }

            else -> {
                Button(onClick = { callbacks.onPlay(fileName) }) {
                    Text(stringResource(R.string.playback_play))
                }
            }
        }
        TextButton(onClick = { callbacks.onOpenBookmark(fileName) }) {
            Text(stringResource(R.string.playback_bookmark))
        }
    }
}

@Composable
private fun EmptyState(filterActive: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(
                if (filterActive) {
                    R.string.dashboard_empty_filtered
                } else {
                    R.string.dashboard_empty_default
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Composable
private fun BookmarkDialog(
    positionMillis: Long,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.bookmark_at,
                        DurationFormat.format(positionMillis / 1000)
                    )
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.bookmark_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.bookmark_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.bookmark_cancel))
            }
        }
    )
}

@Composable
private fun directionSymbol(direction: Direction?): String = when (direction) {
    Direction.INCOMING -> stringResource(R.string.direction_incoming_symbol)
    Direction.OUTGOING -> stringResource(R.string.direction_outgoing_symbol)
    null -> "•"
}

private fun isFilterActive(state: DashboardUiState): Boolean =
    state.allEntries.isNotEmpty() &&
        (state.filterState.callTypeFilter != CallTypeFilter.ALL ||
            state.filterState.directionFilter != DirectionFilter.ALL ||
            state.filterState.searchText.isNotBlank())

@Preview(showBackground = true)
@Composable
private fun DashboardEmptyPreview() {
    UnifiedRecorderTheme {
        DashboardScreen(
            state = DashboardUiState(
                allEntries = emptyList(),
                playback = PlaybackState()
            ),
            callbacks = DashboardCallbacks()
        )
    }
}
