package com.personal.unifiedrecorder.core.model

/**
 * Parsed representation of a filename following the grammar
 * `[CALL_TYPE]_[DIRECTION]_[YYYYMMDD]_[HHMMSS]` with an optional collision suffix.
 */
data class ParsedFilename(
    val callType: CallType,
    val direction: Direction,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val collisionSuffix: Int? = null
)

/**
 * A recording surfaced in the dashboard.
 * [parsed] is null when the file name does not conform to the grammar.
 * [metadata] is null when the companion is missing or unparseable (Req 7.5).
 */
data class RecordingEntry(
    val fileName: String,
    val parsed: ParsedFilename?,
    val metadata: MetadataCompanion?
)

/** Current dashboard filter/search selections. */
data class DashboardFilterState(
    val callTypeFilter: CallTypeFilter = CallTypeFilter.ALL,
    val directionFilter: DirectionFilter = DirectionFilter.ALL,
    val searchText: String = ""
)

/** Configuration for a single capture session. */
data class CaptureConfig(
    val executionMode: ExecutionMode,
    val format: AudioFormat,
    val sampleRate: Int = 44_100,
    val silenceThreshold: Int = 1_500
)
