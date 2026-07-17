package com.personal.unifiedrecorder.core.model

/**
 * Append-only status/diagnostic events emitted by the capture pipeline.
 * The status log is never truncated or rewritten (Property 13).
 */
sealed interface StatusEntry {
    val timestampMillis: Long

    data class CaptureStarted(
        override val timestampMillis: Long,
        val mode: ExecutionMode
    ) : StatusEntry

    data class CaptureStopped(
        override val timestampMillis: Long,
        val abnormal: Boolean
    ) : StatusEntry

    data class PermissionMissing(
        override val timestampMillis: Long,
        val permission: RuntimePermission
    ) : StatusEntry

    data class PathAttempt(
        override val timestampMillis: Long,
        val attempt: Int,
        val success: Boolean
    ) : StatusEntry

    data class PathSucceeded(
        override val timestampMillis: Long
    ) : StatusEntry

    data class CaptureUnestablished(
        override val timestampMillis: Long
    ) : StatusEntry

    data class FormatFallback(
        override val timestampMillis: Long,
        val from: AudioFormat,
        val to: AudioFormat
    ) : StatusEntry

    data class StorageFailure(
        override val timestampMillis: Long,
        val reason: String
    ) : StatusEntry

    data class MetadataWriteFailure(
        override val timestampMillis: Long
    ) : StatusEntry
}
