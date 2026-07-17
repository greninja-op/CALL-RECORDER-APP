package com.personal.unifiedrecorder.core.logic

import com.personal.unifiedrecorder.core.model.AudioFormat
import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.ParsedFilename

/**
 * Encodes and parses recording file names following the grammar (Requirements 6.2, 7.5):
 *
 * ```
 * name      := CALL_TYPE "_" DIRECTION "_" DATE "_" TIME [ "_" SUFFIX ] "." EXT
 * CALL_TYPE := "CELLULAR" | "WHATSAPP"
 * DIRECTION := "INCOMING" | "OUTGOING"
 * DATE      := YYYYMMDD   (8 digits)
 * TIME      := HHMMSS     (6 digits)
 * SUFFIX    := positive integer, added only on collision
 * EXT       := "wav" | "mp4" | "aac"
 * ```
 *
 * Pure and deterministic: no clock access and no `android.*` dependencies.
 */
object FilenameCodec {

    /**
     * Build a unique file name for the given call fields and [format].
     *
     * The base name is `CALL_TYPE_DIRECTION_YYYYMMDD_HHMMSS.ext`. If that name (or a
     * suffixed variant) already appears in [existingNames], a distinguishing numeric suffix
     * is appended before the extension (`..._HHMMSS_1.ext`, `..._HHMMSS_2.ext`, ...) until
     * the result is unique.
     */
    fun encode(
        callType: CallType,
        direction: Direction,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        format: AudioFormat,
        existingNames: Set<String> = emptySet(),
    ): String {
        val date = "%04d%02d%02d".format(year, month, day)
        val time = "%02d%02d%02d".format(hour, minute, second)
        val stem = "${callType.name}_${direction.name}_${date}_$time"
        val ext = extensionOf(format)

        var candidate = "$stem.$ext"
        var suffix = 1
        while (candidate in existingNames) {
            candidate = "${stem}_$suffix.$ext"
            suffix++
        }
        return candidate
    }

    /**
     * Convenience overload that encodes from a [ParsedFilename]. The
     * [ParsedFilename.collisionSuffix] on the input is ignored; any required suffix is
     * recomputed from [existingNames].
     */
    fun encode(
        parsed: ParsedFilename,
        format: AudioFormat,
        existingNames: Set<String> = emptySet(),
    ): String = encode(
        callType = parsed.callType,
        direction = parsed.direction,
        year = parsed.year,
        month = parsed.month,
        day = parsed.day,
        hour = parsed.hour,
        minute = parsed.minute,
        second = parsed.second,
        format = format,
        existingNames = existingNames,
    )

    /**
     * Parse a file name into a [ParsedFilename], or return `null` when [name] does not
     * conform to the grammar (unknown extension, wrong token count, non-numeric or
     * out-of-range date/time fields, etc.).
     */
    fun parse(name: String): ParsedFilename? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        val ext = name.substring(dot + 1).lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) return null

        val stem = name.substring(0, dot)
        val tokens = stem.split("_")
        if (tokens.size != 4 && tokens.size != 5) return null

        val callType = CALL_TYPES[tokens[0]] ?: return null
        val direction = DIRECTIONS[tokens[1]] ?: return null

        val date = tokens[2]
        val time = tokens[3]
        if (date.length != 8 || !date.all { it.isDigit() }) return null
        if (time.length != 6 || !time.all { it.isDigit() }) return null

        val year = date.substring(0, 4).toInt()
        val month = date.substring(4, 6).toInt()
        val day = date.substring(6, 8).toInt()
        val hour = time.substring(0, 2).toInt()
        val minute = time.substring(2, 4).toInt()
        val second = time.substring(4, 6).toInt()

        if (month !in 1..12 || day !in 1..31) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null

        val suffix: Int? = if (tokens.size == 5) {
            val raw = tokens[4]
            if (raw.isEmpty() || !raw.all { it.isDigit() }) return null
            val value = raw.toInt()
            if (value <= 0) return null
            value
        } else {
            null
        }

        return ParsedFilename(
            callType = callType,
            direction = direction,
            year = year,
            month = month,
            day = day,
            hour = hour,
            minute = minute,
            second = second,
            collisionSuffix = suffix,
        )
    }

    /** The file extension used for a given [AudioFormat]. */
    fun extensionOf(format: AudioFormat): String = when (format) {
        AudioFormat.WAV -> "wav"
        AudioFormat.MP4 -> "mp4"
        AudioFormat.AAC -> "aac"
    }

    private val SUPPORTED_EXTENSIONS: Set<String> = setOf("wav", "mp4", "aac")
    private val CALL_TYPES: Map<String, CallType> = CallType.entries.associateBy { it.name }
    private val DIRECTIONS: Map<String, Direction> = Direction.entries.associateBy { it.name }
}
