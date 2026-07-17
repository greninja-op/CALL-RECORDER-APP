package com.personal.unifiedrecorder.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolved (or fallback) identity information for the remote party.
 * Unresolved fields default to the literal "UNKNOWN" (Req 6.10).
 */
@Serializable
data class IdentityContext(
    @SerialName("phone_number") val phoneNumber: String = "UNKNOWN",
    @SerialName("contact_name") val contactName: String = "UNKNOWN"
)

/**
 * Describes how the audio was captured and processed.
 * [dspFilterId] is present only for [ExecutionMode.UNROOTED_LOUDSPEAKER] (Req 4.4).
 */
@Serializable
data class AudioProfile(
    @SerialName("execution_mode") val executionMode: ExecutionMode,
    @SerialName("dsp_filter_id") val dspFilterId: String? = null,
    @SerialName("skipped_silent_seconds") val skippedSilentSeconds: Int = 0
)

/** A user-created timestamped bookmark within a recording. */
@Serializable
data class Bookmark(
    @SerialName("timestamp_ms") val timestampMillis: Long,
    @SerialName("text") val text: String
)

/** User-authored annotations attached to a recording. */
@Serializable
data class UserAnnotations(
    @SerialName("bookmarks") val bookmarks: List<Bookmark> = emptyList(),
    @SerialName("category_tags") val categoryTags: List<String> = emptyList()
)

/**
 * The companion JSON document stored adjacent to each recording, sharing the
 * audio file's base name with a `.json` extension (Req 6.9).
 */
@Serializable
data class MetadataCompanion(
    @SerialName("file_version") val fileVersion: String = "1.0",
    @SerialName("call_type") val callType: CallType,
    @SerialName("direction") val direction: Direction,
    @SerialName("identity") val identity: IdentityContext,
    @SerialName("timestamp_start") val timestampStartMillis: Long,
    @SerialName("duration_seconds") val durationSeconds: Long,
    @SerialName("file_extension") val fileExtension: String,
    @SerialName("audio_profile") val audioProfile: AudioProfile,
    @SerialName("user_annotations") val userAnnotations: UserAnnotations = UserAnnotations()
)
