package com.personal.unifiedrecorder.core.data

import com.personal.unifiedrecorder.core.model.MetadataCompanion
import kotlinx.serialization.json.Json

/**
 * Serializes and deserializes [MetadataCompanion] to/from the companion JSON
 * schema defined in the design (Req 6.9, 6.10, 7.4, 7.8).
 *
 * The field names on the JSON are taken from the `@SerialName` annotations on
 * the model types, so this codec only configures the [Json] instance and the
 * companion-file naming convention.
 */
object MetadataCodec {

    /**
     * Shared JSON configuration.
     *
     * - `encodeDefaults = true` guarantees that fields carrying default values
     *   (notably the `UNKNOWN` fallbacks on [com.personal.unifiedrecorder.core.model.IdentityContext]
     *   and the file version / annotations) are always written to disk rather
     *   than omitted, so a companion file always contains every field (Req 6.10).
     * - `ignoreUnknownKeys = true` keeps decoding tolerant of forward-compatible
     *   additions in the on-disk schema.
     */
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** Serialize [metadata] to its companion JSON string. Never returns null. */
    fun encode(metadata: MetadataCompanion): String =
        json.encodeToString(MetadataCompanion.serializer(), metadata)

    /**
     * Deserialize a companion JSON string.
     *
     * Returns `null` for any unparseable input rather than throwing, so callers
     * (e.g. the dashboard) can render a recording from its file name when the
     * companion is missing or corrupt (Req 7.5).
     */
    fun decode(jsonText: String): MetadataCompanion? =
        try {
            json.decodeFromString(MetadataCompanion.serializer(), jsonText)
        } catch (_: Exception) {
            null
        }

    /**
     * Derives the companion file name for a given audio file: the audio file's
     * base name (its final extension removed, if any) with a `.json` extension
     * (Req 6.9). The companion therefore lives adjacent to the audio file in the
     * Target_Directory sharing its base name.
     */
    fun companionFileNameFor(audioName: String): String {
        val dot = audioName.lastIndexOf('.')
        val base = if (dot > 0) audioName.substring(0, dot) else audioName
        return "$base.json"
    }
}
