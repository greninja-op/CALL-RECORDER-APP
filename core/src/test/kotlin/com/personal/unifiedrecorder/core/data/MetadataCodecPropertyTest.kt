package com.personal.unifiedrecorder.core.data

import com.personal.unifiedrecorder.core.model.AudioProfile
import com.personal.unifiedrecorder.core.model.Bookmark
import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.ExecutionMode
import com.personal.unifiedrecorder.core.model.IdentityContext
import com.personal.unifiedrecorder.core.model.MetadataCompanion
import com.personal.unifiedrecorder.core.model.UserAnnotations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.checkAll

private const val ITERATIONS = 100

// ---------------------------------------------------------------------------
// Inline generators
// ---------------------------------------------------------------------------

/** Either a resolved value or the literal "UNKNOWN" fallback (alphanumeric, JSON-safe). */
private fun identityFieldArb(): Arb<String> = arbitrary { rs ->
    if (rs.random.nextBoolean()) "UNKNOWN"
    else Arb.string(1, 12, Codepoint.az()).bind()
}

private fun identityArb(): Arb<IdentityContext> = arbitrary {
    IdentityContext(
        phoneNumber = identityFieldArb().bind(),
        contactName = identityFieldArb().bind()
    )
}

private fun bookmarkArb(): Arb<Bookmark> = arbitrary {
    Bookmark(
        timestampMillis = Arb.long(0L, 10_000_000L).bind(),
        text = Arb.string(0, 20).bind()
    )
}

private fun annotationsArb(): Arb<UserAnnotations> = arbitrary {
    UserAnnotations(
        bookmarks = Arb.list(bookmarkArb(), 0..5).bind(),
        categoryTags = Arb.list(Arb.string(1, 10), 0..5).bind()
    )
}

private fun audioProfileArb(): Arb<AudioProfile> = arbitrary { rs ->
    val mode = Arb.enum<ExecutionMode>().bind()
    val dspId = if (mode == ExecutionMode.UNROOTED_LOUDSPEAKER && rs.random.nextBoolean())
        "agc_lowpass_v1" else null
    AudioProfile(
        executionMode = mode,
        dspFilterId = dspId,
        skippedSilentSeconds = Arb.int(0, 3600).bind()
    )
}

/** Arb<MetadataCompanion> covering UNKNOWN identity fields and rich annotations. */
private fun metadataArb(): Arb<MetadataCompanion> = arbitrary {
    MetadataCompanion(
        fileVersion = "1.0",
        callType = Arb.enum<CallType>().bind(),
        direction = Arb.enum<Direction>().bind(),
        identity = identityArb().bind(),
        timestampStartMillis = Arb.long(0L, 4_000_000_000_000L).bind(),
        durationSeconds = Arb.long(0L, 36_000L).bind(),
        fileExtension = Arb.of("wav", "mp4", "aac").bind(),
        audioProfile = audioProfileArb().bind(),
        userAnnotations = annotationsArb().bind()
    )
}

class MetadataCodecPropertyTest : FunSpec({

    // Feature: unified-call-recorder, Property 26: For any valid MetadataCompanion, MetadataCodec.decode(encode(m)) SHALL yield an equivalent value, and the companion file name SHALL equal the audio file's base name with a .json extension.
    // Validates: Requirements 6.9
    test("Property 26: metadata round-trip and companion adjacency") {
        checkAll(PropTestConfig(iterations = ITERATIONS), metadataArb(), Arb.of("wav", "mp4", "aac")) { md, ext ->
            val roundTripped = MetadataCodec.decode(MetadataCodec.encode(md))
            roundTripped shouldBe md

            val base = "CELLULAR_INCOMING_20250101_120000"
            MetadataCodec.companionFileNameFor("$base.$ext") shouldBe "$base.json"
        }
    }

    // Feature: unified-call-recorder, Property 27: For any metadata in which Direction or any Identity_Context field cannot be resolved, the serialized Metadata_Companion SHALL contain the literal UNKNOWN for each unresolved field and a companion file SHALL always be produced (never omitted).
    // Validates: Requirements 6.10
    test("Property 27: unresolved fields fall back to UNKNOWN and file always written") {
        checkAll(PropTestConfig(iterations = ITERATIONS), metadataArb()) { md ->
            val encoded = MetadataCodec.encode(md)

            // A companion is always produced (non-empty serialized document).
            encoded.isNotEmpty() shouldBe true

            // Unresolved identity fields serialize as the literal UNKNOWN, and the
            // fields are always present (encodeDefaults) so nothing is omitted.
            encoded shouldContain "\"phone_number\":\"${md.identity.phoneNumber}\""
            encoded shouldContain "\"contact_name\":\"${md.identity.contactName}\""
            if (md.identity.phoneNumber == "UNKNOWN") encoded shouldContain "\"phone_number\":\"UNKNOWN\""
            if (md.identity.contactName == "UNKNOWN") encoded shouldContain "\"contact_name\":\"UNKNOWN\""

            // The round-trip preserves the (possibly UNKNOWN) values exactly.
            MetadataCodec.decode(encoded) shouldBe md
        }
    }

    // Feature: unified-call-recorder, Property 34: For any sequence of bookmark/category-tag create or edit operations applied to a MetadataCompanion, serializing and then deserializing the user_annotations SHALL reflect exactly the applied edits.
    // Validates: Requirements 7.8
    test("Property 34: annotation edits round-trip through the companion") {
        checkAll(
            PropTestConfig(iterations = ITERATIONS),
            metadataArb(),
            Arb.list(bookmarkArb(), 0..6),
            Arb.list(Arb.string(1, 10), 0..6)
        ) { md, newBookmarks, newTags ->
            // Apply annotation edits.
            val edited = md.copy(
                userAnnotations = UserAnnotations(bookmarks = newBookmarks, categoryTags = newTags)
            )
            val decoded = MetadataCodec.decode(MetadataCodec.encode(edited))
            decoded?.userAnnotations?.bookmarks shouldBe newBookmarks
            decoded?.userAnnotations?.categoryTags shouldBe newTags
        }
    }
})
