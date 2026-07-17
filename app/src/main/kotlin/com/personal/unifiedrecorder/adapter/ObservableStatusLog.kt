package com.personal.unifiedrecorder.adapter

import com.personal.unifiedrecorder.core.model.StatusEntry
import com.personal.unifiedrecorder.core.port.StatusLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [StatusLog] that surfaces entries to an observable [StateFlow] for the UI while keeping the
 * history strictly append-only (Property 13 / Requirement 3.7): [record] only ever appends and
 * never removes or rewrites prior entries.
 *
 * Each update publishes a fresh immutable list so observers never see the backing store mutate.
 */
class ObservableStatusLog : StatusLog {

    private val _entries = MutableStateFlow<List<StatusEntry>>(emptyList())

    /** Append-only, most-recent-last view of every recorded status entry. */
    val entries: StateFlow<List<StatusEntry>> = _entries.asStateFlow()

    @Synchronized
    override fun record(entry: StatusEntry) {
        _entries.value = _entries.value + entry
    }
}
