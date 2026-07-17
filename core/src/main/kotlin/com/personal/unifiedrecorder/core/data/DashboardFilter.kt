package com.personal.unifiedrecorder.core.data

import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.CallTypeFilter
import com.personal.unifiedrecorder.core.model.DashboardFilterState
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.DirectionFilter
import com.personal.unifiedrecorder.core.model.ParsedFilename
import com.personal.unifiedrecorder.core.model.RecordingEntry
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Result of applying a [DashboardFilterState] to a list of recordings. */
data class DashboardResult(
    val entries: List<RecordingEntry>,
    /** True when no entry matched the active criteria — the dashboard empty state (Req 7.9). */
    val isEmpty: Boolean
)

/**
 * Pure filter/search/sort logic backing the dashboard list (Req 7.1, 7.3, 7.5, 7.9).
 *
 * All predicates draw their values from the recording's [MetadataCompanion]
 * when present, otherwise from the fields derived from its file name. The
 * output is always ordered newest-first by recording timestamp.
 */
object DashboardFilter {

    /**
     * Resolves the sortable recording timestamp (epoch millis) for [entry],
     * preferring the companion metadata and falling back to the parsed file
     * name. Entries with neither resolve to [Long.MIN_VALUE] so they sort last.
     */
    fun timestampOf(entry: RecordingEntry): Long {
        entry.metadata?.let { return it.timestampStartMillis }
        entry.parsed?.let { return parsedToEpochMillis(it) }
        return Long.MIN_VALUE
    }

    private fun parsedToEpochMillis(p: ParsedFilename): Long =
        LocalDateTime.of(p.year, p.month, p.day, p.hour, p.minute, p.second)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

    /** Resolves the effective call type: metadata first, then file name. */
    fun callTypeOf(entry: RecordingEntry): CallType? =
        entry.metadata?.callType ?: entry.parsed?.callType

    /** Resolves the effective direction: metadata first, then file name. */
    fun directionOf(entry: RecordingEntry): Direction? =
        entry.metadata?.direction ?: entry.parsed?.direction

    /**
     * The set of strings the free-text search is matched against: the contact
     * name and phone number from the companion when present, otherwise the file
     * name (the only contact-identifying text derivable without metadata).
     */
    fun searchableFields(entry: RecordingEntry): List<String> {
        val md = entry.metadata
        return if (md != null) {
            listOf(md.identity.contactName, md.identity.phoneNumber)
        } else {
            listOf(entry.fileName)
        }
    }

    private fun matchesCallType(entry: RecordingEntry, filter: CallTypeFilter): Boolean =
        when (filter) {
            CallTypeFilter.ALL -> true
            CallTypeFilter.WHATSAPP -> callTypeOf(entry) == CallType.WHATSAPP
            CallTypeFilter.CELLULAR -> callTypeOf(entry) == CallType.CELLULAR
        }

    private fun matchesDirection(entry: RecordingEntry, filter: DirectionFilter): Boolean =
        when (filter) {
            DirectionFilter.ALL -> true
            DirectionFilter.INCOMING -> directionOf(entry) == Direction.INCOMING
            DirectionFilter.OUTGOING -> directionOf(entry) == Direction.OUTGOING
        }

    private fun matchesSearch(entry: RecordingEntry, searchText: String): Boolean {
        val needle = searchText.trim()
        if (needle.isEmpty()) return true
        return searchableFields(entry).any { it.contains(needle, ignoreCase = true) }
    }

    /** True when [entry] satisfies every active criterion of [state]. */
    fun matches(entry: RecordingEntry, state: DashboardFilterState): Boolean =
        matchesCallType(entry, state.callTypeFilter) &&
            matchesDirection(entry, state.directionFilter) &&
            matchesSearch(entry, state.searchText)

    /**
     * Applies [state] to [entries]: keeps only matching entries, orders them
     * newest-first by recording timestamp, and reports the empty state. Output
     * is always a subset of the input, so no content from outside the bound
     * directory is ever introduced (source isolation, Req 7.1).
     */
    fun apply(entries: List<RecordingEntry>, state: DashboardFilterState): DashboardResult {
        val filtered = entries
            .filter { matches(it, state) }
            .sortedByDescending { timestampOf(it) }
        return DashboardResult(entries = filtered, isEmpty = filtered.isEmpty())
    }
}

/**
 * Formats and parses call durations as zero-padded `MM:SS` for durations under
 * one hour (Req 7.4).
 */
object DurationFormat {

    /** Formats [totalSeconds] as `MM:SS` with zero-padded minutes and seconds. */
    fun format(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    /**
     * Parses a `MM:SS` string back into a total number of seconds. Returns null
     * if the text is not well-formed `MM:SS`.
     */
    fun parse(text: String): Long? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val minutes = parts[0].toLongOrNull() ?: return null
        val seconds = parts[1].toLongOrNull() ?: return null
        if (minutes < 0 || seconds < 0 || seconds >= 60) return null
        return minutes * 60 + seconds
    }
}
