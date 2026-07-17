package com.personal.unifiedrecorder.ui.dashboard

import com.personal.unifiedrecorder.core.model.AudioProfile
import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.ExecutionMode
import com.personal.unifiedrecorder.core.model.IdentityContext
import com.personal.unifiedrecorder.core.model.MetadataCompanion
import com.personal.unifiedrecorder.core.model.ParsedFilename
import com.personal.unifiedrecorder.core.model.RecordingEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * JVM unit tests for the pure [RecordingDisplay] mapper (Task 24.2). These run
 * on the JVM via `testDebugUnitTest` with no device because the mapper depends
 * only on core data types and `java.time` — it contains no `android.*` usage.
 *
 * Covers the presentation contract for Req 7.4 (MM:SS duration) and Req 7.5
 * (metadata-first rendering with filename fallback and placeholders).
 */
class RecordingDisplayTest : FunSpec({

    fun metadata(
        callType: CallType = CallType.CELLULAR,
        direction: Direction = Direction.INCOMING,
        contactName: String = "Alice",
        phoneNumber: String = "+15551234567",
        timestampStartMillis: Long = 1_700_000_000_000L,
        durationSeconds: Long = 142L
    ): MetadataCompanion = MetadataCompanion(
        callType = callType,
        direction = direction,
        identity = IdentityContext(phoneNumber = phoneNumber, contactName = contactName),
        timestampStartMillis = timestampStartMillis,
        durationSeconds = durationSeconds,
        fileExtension = "wav",
        audioProfile = AudioProfile(executionMode = ExecutionMode.UNROOTED_LOUDSPEAKER)
    )

    fun parsed(direction: Direction = Direction.OUTGOING): ParsedFilename = ParsedFilename(
        callType = CallType.WHATSAPP,
        direction = direction,
        year = 2024, month = 1, day = 2,
        hour = 13, minute = 45, second = 30
    )

    test("maps metadata into presentation display fields") {
        val entry = RecordingEntry(
            fileName = "CELLULAR_INCOMING_20231114_221320.wav",
            parsed = null,
            metadata = metadata(contactName = "Alice", phoneNumber = "+15551234567", durationSeconds = 142L)
        )

        val row = RecordingDisplay.toRow(entry)

        row.fileName shouldBe "CELLULAR_INCOMING_20231114_221320.wav"
        row.displayName shouldBe "Alice"
        row.number shouldBe "+15551234567"
        row.direction shouldBe Direction.INCOMING
        row.durationLabel shouldBe "02:22"
        // Timestamp is present, so a formatted local date-time label is produced.
        row.dateTimeLabel.shouldNotBeNull()
    }

    test("falls back to file name and null fields when metadata is absent and name is non-conforming") {
        val entry = RecordingEntry(
            fileName = "arbitrary_recording.wav",
            parsed = null,
            metadata = null
        )

        val row = RecordingDisplay.toRow(entry)

        // Missing metadata renders from the file name and is never hidden (Req 7.5).
        row.displayName shouldBe "arbitrary_recording.wav"
        row.number.shouldBeNull()
        row.durationLabel.shouldBeNull()
        row.direction.shouldBeNull()
        // No metadata and a non-conforming name -> no resolvable timestamp.
        row.dateTimeLabel.shouldBeNull()
    }

    test("derives direction and date-time from the parsed file name when metadata is absent") {
        val entry = RecordingEntry(
            fileName = "WHATSAPP_OUTGOING_20240102_134530.wav",
            parsed = parsed(direction = Direction.OUTGOING),
            metadata = null
        )

        val row = RecordingDisplay.toRow(entry)

        // Direction resolves from the parsed name (Req 7.5).
        row.direction shouldBe Direction.OUTGOING
        // A parsed timestamp yields a formatted label even without metadata.
        row.dateTimeLabel.shouldNotBeNull()
        // Number and duration remain unavailable without metadata.
        row.number.shouldBeNull()
        row.durationLabel.shouldBeNull()
        row.displayName shouldBe "WHATSAPP_OUTGOING_20240102_134530.wav"
    }

    test("substitutes placeholders when identity fields are the literal UNKNOWN") {
        val entry = RecordingEntry(
            fileName = "CELLULAR_INCOMING_20231114_221320.wav",
            parsed = null,
            metadata = metadata(contactName = "UNKNOWN", phoneNumber = "UNKNOWN")
        )

        val row = RecordingDisplay.toRow(entry)

        // UNKNOWN contact name -> display falls back to the file name.
        row.displayName shouldBe "CELLULAR_INCOMING_20231114_221320.wav"
        // UNKNOWN number -> null so the composable can render a placeholder.
        row.number.shouldBeNull()
    }

    test("formats duration as zero-padded MM:SS including the 59:59 boundary") {
        val shortRow = RecordingDisplay.toRow(
            RecordingEntry("a.wav", parsed = null, metadata = metadata(durationSeconds = 5L))
        )
        shortRow.durationLabel shouldBe "00:05"

        val midRow = RecordingDisplay.toRow(
            RecordingEntry("b.wav", parsed = null, metadata = metadata(durationSeconds = 142L))
        )
        midRow.durationLabel shouldBe "02:22"

        val maxRow = RecordingDisplay.toRow(
            RecordingEntry("c.wav", parsed = null, metadata = metadata(durationSeconds = 3599L))
        )
        maxRow.durationLabel shouldBe "59:59"
    }

    test("renders both call directions from metadata") {
        val incoming = RecordingDisplay.toRow(
            RecordingEntry("in.wav", parsed = null, metadata = metadata(direction = Direction.INCOMING))
        )
        incoming.direction shouldBe Direction.INCOMING

        val outgoing = RecordingDisplay.toRow(
            RecordingEntry("out.wav", parsed = null, metadata = metadata(direction = Direction.OUTGOING))
        )
        outgoing.direction shouldBe Direction.OUTGOING
    }
})
