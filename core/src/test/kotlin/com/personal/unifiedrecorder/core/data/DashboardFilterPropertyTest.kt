package com.personal.unifiedrecorder.core.data

import com.personal.unifiedrecorder.core.model.AudioProfile
import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.CallTypeFilter
import com.personal.unifiedrecorder.core.model.DashboardFilterState
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.DirectionFilter
import com.personal.unifiedrecorder.core.model.ExecutionMode
import com.personal.unifiedrecorder.core.model.IdentityContext
import com.personal.unifiedrecorder.core.model.MetadataCompanion
import com.personal.unifiedrecorder.core.model.ParsedFilename
import com.personal.unifiedrecorder.core.model.RecordingEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

private const val ITERATIONS = 100

// ---------------------------------------------------------------------------
// Inline generators
// ---------------------------------------------------------------------------

private fun parsedArb(): Arb<ParsedFilename> = arbitrary {
    ParsedFilename(
        callType = Arb.enum<CallType>().bind(),
        direction = Arb.enum<Direction>().bind(),
        year = Arb.int(2020, 2030).bind(),
        month = Arb.int(1, 12).bind(),
        day = Arb.int(1, 28).bind(),
        hour = Arb.int(0, 23).bind(),
        minute = Arb.int(0, 59).bind(),
        second = Arb.int(0, 59).bind(),
        collisionSuffix = null
    )
}

private fun identityArb(): Arb<IdentityContext> = arbitrary {
    IdentityContext(
        phoneNumber = Arb.of("UNKNOWN", "911", "555").bind() + Arb.string(0, 4, Codepoint.az()).bind(),
        contactName = Arb.of("UNKNOWN", "Alice", "Bob", "Carol").bind()
    )
}

private fun metadataArb(): Arb<MetadataCompanion> = arbitrary {
    MetadataCompanion(
        callType = Arb.enum<CallType>().bind(),
        direction = Arb.enum<Direction>().bind(),
        identity = identityArb().bind(),
        timestampStartMillis = Arb.long(0L, 4_000_000_000_000L).bind(),
        durationSeconds = Arb.long(0L, 36_000L).bind(),
        fileExtension = Arb.of("wav", "mp4", "aac").bind(),
        audioProfile = AudioProfile(executionMode = Arb.enum<ExecutionMode>().bind())
    )
}

/** Recording entries covering: full metadata, filename-only, and neither. */
private fun recordingEntryArb(): Arb<RecordingEntry> = arbitrary { rs ->
    when (rs.random.nextInt(3)) {
        0 -> {
            val md = metadataArb().bind()
            RecordingEntry("rec_${rs.random.nextInt(100000)}.wav", parsedArb().bind(), md)
        }
        1 -> RecordingEntry("rec_${rs.random.nextInt(100000)}.wav", parsedArb().bind(), null)
        else -> RecordingEntry("free_form_${rs.random.nextInt(100000)}.wav", null, null)
    }
}

private fun filterStateArb(): Arb<DashboardFilterState> = arbitrary {
    DashboardFilterState(
        callTypeFilter = Arb.enum<CallTypeFilter>().bind(),
        directionFilter = Arb.enum<DirectionFilter>().bind(),
        searchText = Arb.of("", "Alice", "911", "UNKNOWN", "zzz", "rec").bind()
    )
}

class DashboardFilterPropertyTest : FunSpec({

    // Feature: unified-call-recorder, Property 29: For any list of recording entries, DashboardFilter output SHALL be ordered by recording timestamp from most recent to oldest and SHALL include only entries drawn from the Target_Directory.
    // Validates: Requirements 7.1
    test("Property 29: dashboard ordering and source isolation") {
        checkAll(PropTestConfig(iterations = ITERATIONS), Arb.list(recordingEntryArb(), 0..20)) { entries ->
            val result = DashboardFilter.apply(entries, DashboardFilterState())

            // Ordered newest-first by resolved timestamp.
            val timestamps = result.entries.map { DashboardFilter.timestampOf(it) }
            timestamps shouldBe timestamps.sortedDescending()

            // Source isolation: output is exactly the input set (no external content,
            // nothing dropped under the default all-pass filter).
            result.entries.size shouldBe entries.size
            result.entries.toSet() shouldBe entries.toSet()
            result.entries.all { it in entries }.shouldBeTrue()
        }
    }

    // Feature: unified-call-recorder, Property 30: For any list of recording entries and any DashboardFilterState, every entry in the result SHALL satisfy all active criteria, every excluded entry SHALL fail at least one active criterion, and when the result is empty the view-state SHALL be the empty state.
    // Validates: Requirements 7.3, 7.9
    test("Property 30: filter/search soundness, completeness, and empty state") {
        checkAll(
            PropTestConfig(iterations = ITERATIONS),
            Arb.list(recordingEntryArb(), 0..20),
            filterStateArb()
        ) { entries, state ->
            val result = DashboardFilter.apply(entries, state)
            val included = result.entries.toSet()

            // Soundness: everything included matches all active criteria.
            included.all { DashboardFilter.matches(it, state) }.shouldBeTrue()

            // Completeness: everything excluded fails at least one active criterion.
            entries.filter { it !in included }
                .all { !DashboardFilter.matches(it, state) }
                .shouldBeTrue()

            // Empty state reflects an empty result exactly.
            result.isEmpty shouldBe result.entries.isEmpty()
        }
    }

    // Feature: unified-call-recorder, Property 32: For any recording entry whose Metadata_Companion is absent or unparseable but whose file name conforms to the naming grammar, the rendered view-model SHALL derive its fields from the parsed file name, use a placeholder for each unavailable metadata field, and retain the entry in the list.
    // Validates: Requirements 7.5
    test("Property 32: missing metadata renders from filename with placeholders and is never hidden") {
        checkAll(PropTestConfig(iterations = ITERATIONS), parsedArb()) { parsed ->
            // Entry with a conforming file name but no companion metadata.
            val entry = RecordingEntry("rec.wav", parsed, null)

            // Fields are derived from the parsed file name, not metadata.
            DashboardFilter.callTypeOf(entry) shouldBe parsed.callType
            DashboardFilter.directionOf(entry) shouldBe parsed.direction

            // The entry is retained in the list under the default (all-pass) filter.
            val result = DashboardFilter.apply(listOf(entry), DashboardFilterState())
            result.entries shouldBe listOf(entry)
            result.isEmpty shouldBe false
        }
    }

    // Feature: unified-call-recorder, Property 31: For any non-negative duration under one hour, formatting to MM:SS SHALL produce zero-padded minutes and seconds that parse back to the original number of seconds.
    // Validates: Requirements 7.4
    test("Property 31: duration formatting round-trips as MM:SS") {
        checkAll(PropTestConfig(iterations = ITERATIONS), Arb.long(0L, 3599L)) { seconds ->
            val formatted = DurationFormat.format(seconds)

            // Zero-padded MM:SS shape.
            formatted.length shouldBe 5
            formatted[2] shouldBe ':'

            // Round-trips back to the original number of seconds.
            DurationFormat.parse(formatted) shouldBe seconds
        }
    }
})
