package com.personal.unifiedrecorder.ui.dashboard

import com.personal.unifiedrecorder.core.data.DashboardFilter
import com.personal.unifiedrecorder.core.data.DurationFormat
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.RecordingEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A flattened, presentation-ready view of a [RecordingEntry] for a dashboard
 * row. Values are resolved from the companion metadata when present and fall
 * back to fields derived from the file name otherwise (Req 7.5). Null fields
 * signal "unavailable" so the composable can substitute a placeholder.
 */
data class RecordingRow(
    val fileName: String,
    /** Contact name, or the file name when no contact name is available. */
    val displayName: String,
    /** Phone number, or null when unavailable. */
    val number: String?,
    /** Formatted local date-time, or null when no timestamp could be resolved. */
    val dateTimeLabel: String?,
    /** Resolved direction, or null when unknown. */
    val direction: Direction?,
    /** `MM:SS` duration, or null when the duration is unknown. */
    val durationLabel: String?
)

/** Builds presentation rows from core [RecordingEntry] values. Pure (no Android). */
object RecordingDisplay {

    private const val UNKNOWN = "UNKNOWN"
    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm")

    fun toRow(entry: RecordingEntry): RecordingRow {
        val metadata = entry.metadata
        val contactName = metadata?.identity?.contactName?.takeIf { it != UNKNOWN }
        val number = metadata?.identity?.phoneNumber?.takeIf { it != UNKNOWN }

        val timestamp = DashboardFilter.timestampOf(entry)
        val dateTimeLabel = if (timestamp == Long.MIN_VALUE) {
            null
        } else {
            Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(dateTimeFormatter)
        }

        val durationLabel = metadata?.durationSeconds?.let { DurationFormat.format(it) }

        return RecordingRow(
            fileName = entry.fileName,
            displayName = contactName ?: entry.fileName,
            number = number,
            dateTimeLabel = dateTimeLabel,
            direction = DashboardFilter.directionOf(entry),
            durationLabel = durationLabel
        )
    }
}
