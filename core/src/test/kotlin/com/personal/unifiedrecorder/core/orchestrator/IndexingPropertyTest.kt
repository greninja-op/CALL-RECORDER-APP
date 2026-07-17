package com.personal.unifiedrecorder.core.orchestrator

import com.personal.unifiedrecorder.core.data.MetadataCodec
import com.personal.unifiedrecorder.core.fake.FakeClock
import com.personal.unifiedrecorder.core.fake.FakeDocumentStore
import com.personal.unifiedrecorder.core.fake.FakeStatusLog
import com.personal.unifiedrecorder.core.model.AudioProfile
import com.personal.unifiedrecorder.core.model.Bookmark
import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.ExecutionMode
import com.personal.unifiedrecorder.core.model.IdentityContext
import com.personal.unifiedrecorder.core.model.MetadataCompanion
import com.personal.unifiedrecorder.core.model.UserAnnotations
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest

private fun text(): Arb<String> = Arb.string(0..10, Arb.alphanumeric())

private fun metadataArb(): Arb<MetadataCompanion> = arbitrary { rs ->
    val callType = Arb.of(CallType.entries.toList()).sample(rs).value
    val direction = Arb.of(Direction.entries.toList()).sample(rs).value
    val mode = Arb.of(ExecutionMode.entries.toList()).sample(rs).value
    val dspFilterId = if (mode == ExecutionMode.UNROOTED_LOUDSPEAKER) "agc_lowpass_v1" else null
    val bookmarks = Arb.list(
        arbitrary { r -> Bookmark(Arb.long(0L..600_000L).sample(r).value, text().sample(r).value) },
        0..3
    ).sample(rs).value
    val tags = Arb.list(text(), 0..3).sample(rs).value
    MetadataCompanion(
        callType = callType,
        direction = direction,
        identity = IdentityContext(phoneNumber = text().sample(rs).value, contactName = text().sample(rs).value),
        timestampStartMillis = Arb.long(0L..4_000_000_000_000L).sample(rs).value,
        durationSeconds = Arb.long(0L..3_600L).sample(rs).value,
        fileExtension = "wav",
        audioProfile = AudioProfile(
            executionMode = mode,
            dspFilterId = dspFilterId,
            skippedSilentSeconds = Arb.long(0L..600L).sample(rs).value.toInt()
        ),
        userAnnotations = UserAnnotations(bookmarks = bookmarks, categoryTags = tags)
    )
}

class IndexingPropertyTest : StringSpec({

    // Feature: unified-call-recorder, Property 41: Directory indexing restores history from companions
    // Validates: Requirements 9.3
    "Property 41: indexing the bound directory restores recording entries whose metadata matches the seeded companion files" {
        checkAll(100, Arb.list(metadataArb(), 0..6)) { metadataList ->
            runTest {
                val store = FakeDocumentStore()
                // Seed one audio recording plus its companion per metadata item.
                val expected = metadataList.mapIndexed { i, md ->
                    val audioName = "CELLULAR_INCOMING_20240101_120000_${i + 1}.wav"
                    store.seedRecording(audioName, byteArrayOf(i.toByte()))
                    store.seedCompanion(MetadataCodec.companionFileNameFor(audioName), MetadataCodec.encode(md))
                    audioName to md
                }.toMap()

                val orchestrator = CapturePathOrchestrator(FakeClock(), FakeStatusLog(), store)
                val entries = orchestrator.indexRecordings()

                entries.map { it.fileName }.shouldContainExactlyInAnyOrder(expected.keys)
                entries.forEach { entry ->
                    entry.metadata shouldBe expected[entry.fileName]
                }
            }
        }
    }

    // Feature: unified-call-recorder, Property 25: Storage access is confined to the bound directory
    // Validates: Requirements 6.8
    "Property 25: indexing surfaces only documents under the bound tree and never content residing outside it" {
        checkAll(100, Arb.list(text(), 0..5), Arb.list(text(), 0..5)) { boundStems, externalStems ->
            runTest {
                val store = FakeDocumentStore()
                val boundNames = boundStems.mapIndexed { i, _ -> "WHATSAPP_OUTGOING_20240101_120000_${i + 1}.wav" }.toSet()
                boundNames.forEach { store.seedRecording(it, byteArrayOf(1)) }
                // External content outside the bound tree must never surface.
                val externalNames = externalStems.mapIndexed { i, _ -> "external_$i.wav" }.toSet()
                externalNames.forEach { store.seedExternal(it, byteArrayOf(2)) }

                val orchestrator = CapturePathOrchestrator(FakeClock(), FakeStatusLog(), store)
                val entries = orchestrator.indexRecordings()

                val surfaced = entries.map { it.fileName }.toSet()
                // Only bound recordings surface; nothing external does.
                surfaced shouldBe boundNames
                externalNames.forEach { surfaced.contains(it).shouldBeFalse() }
                // Companions themselves are not surfaced as audio recordings.
                entries.none { it.fileName.endsWith(".json") } shouldBe true
            }
        }
    }
})
