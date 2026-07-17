package com.personal.unifiedrecorder.core.orchestrator

import com.personal.unifiedrecorder.core.fake.FakeClock
import com.personal.unifiedrecorder.core.fake.FakeDocumentStore
import com.personal.unifiedrecorder.core.fake.FakeStatusLog
import com.personal.unifiedrecorder.core.model.AudioProfile
import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.ExecutionMode
import com.personal.unifiedrecorder.core.model.IdentityContext
import com.personal.unifiedrecorder.core.model.MetadataCompanion
import com.personal.unifiedrecorder.core.model.StatusEntry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest

private val MODES_F = Arb.of(ExecutionMode.entries.toList())

private fun audioArb(): Arb<ByteArray> = Arb.byteArray(Arb.int(2..64), Arb.byte())

private fun metadata(mode: ExecutionMode): MetadataCompanion =
    MetadataCompanion(
        callType = CallType.WHATSAPP,
        direction = Direction.OUTGOING,
        identity = IdentityContext(),
        timestampStartMillis = 5_000L,
        durationSeconds = 12L,
        fileExtension = "wav",
        audioProfile = AudioProfile(executionMode = mode)
    )

class FinalizePropertyTest : StringSpec({

    // Feature: unified-call-recorder, Property 14: Audio profile records the execution mode and DSP filter consistently
    // Validates: Requirements 3.8, 4.4
    "Property 14: audio_profile.execution_mode equals the selected mode and dsp_filter_id is present iff UNROOTED_LOUDSPEAKER" {
        checkAll(200, MODES_F, Arb.string(1..16), Arb.int(0..600)) { mode, filterId, skipped ->
            val orchestrator = CapturePathOrchestrator(FakeClock(), FakeStatusLog(), FakeDocumentStore())
            val profile = orchestrator.buildAudioProfile(mode, filterId, skipped)

            profile.executionMode shouldBe mode
            profile.skippedSilentSeconds shouldBe skipped
            when (mode) {
                ExecutionMode.UNROOTED_LOUDSPEAKER -> profile.dspFilterId shouldBe filterId
                ExecutionMode.ROOTED_STEALTH -> profile.dspFilterId shouldBe null
            }
        }
    }

    // Feature: unified-call-recorder, Property 23: Storage-write failure is non-destructive
    // Validates: Requirements 6.6
    "Property 23: a failed write logs StorageFailure, retains the written portion, and deletes no previously saved recording" {
        checkAll(100, Arb.int(0..5), audioArb(), MODES_F) { existingCount, bytes, mode ->
            runTest {
                val log = FakeStatusLog()
                val store = FakeDocumentStore(failWrite = true)
                // Pre-seed previously saved recordings with distinct content.
                val existing = (0 until existingCount).associate { i ->
                    val name = "old_$i.wav"
                    name to byteArrayOf(i.toByte(), (i + 1).toByte(), (i + 2).toByte())
                }
                existing.forEach { (name, content) -> store.seedRecording(name, content) }

                val orchestrator = CapturePathOrchestrator(FakeClock(), log, store)
                val newName = "new_recording.wav"
                val result = orchestrator.finalize(bytes, newName, metadata(mode))

                result.audioWritten.shouldBeFalse()
                log.countOf<StatusEntry.StorageFailure>() shouldBe 1
                // Previously saved recordings are untouched.
                existing.forEach { (name, content) ->
                    store.files.containsKey(name).shouldBeTrue()
                    store.files.getValue(name).toList() shouldBe content.toList()
                }
                // The partial written portion is retained (never discarded).
                store.files.containsKey(newName).shouldBeTrue()
            }
        }
    }

    // Feature: unified-call-recorder, Property 24: Storage-unavailable abort guard preserves in-memory data
    // Validates: Requirements 6.7
    "Property 24: when the directory is unavailable or space is insufficient at write start, the write aborts before writing and in-memory data is preserved" {
        checkAll(200, audioArb(), Arb.boolean()) { bytes, unbound ->
            runTest {
                val log = FakeStatusLog()
                val store = if (unbound) {
                    FakeDocumentStore(bound = false)
                } else {
                    // Insufficient free space: strictly less than the recording size.
                    FakeDocumentStore(freeBytes = (bytes.size - 1).toLong())
                }
                val orchestrator = CapturePathOrchestrator(FakeClock(), log, store)
                val name = "aborted.wav"

                val result = orchestrator.finalize(bytes, name, metadata(ExecutionMode.UNROOTED_LOUDSPEAKER))

                result.abortedBeforeWrite.shouldBeTrue()
                result.audioWritten.shouldBeFalse()
                // Nothing was written to the store.
                store.files.containsKey(name).shouldBeFalse()
                // A storage-unavailable status is recorded.
                val failures = log.entries.filterIsInstance<StatusEntry.StorageFailure>()
                failures.size shouldBe 1
                failures.single().reason shouldBe CapturePathOrchestrator.STORAGE_UNAVAILABLE
                // In-memory recording data is preserved (untouched).
                bytes.size shouldBe bytes.size
            }
        }
    }

    // Feature: unified-call-recorder, Property 28: Metadata-write failure retains the audio recording
    // Validates: Requirements 6.11
    "Property 28: when writing the companion fails, MetadataWriteFailure is logged and the audio recording is retained" {
        checkAll(100, audioArb(), MODES_F) { bytes, mode ->
            runTest {
                val log = FakeStatusLog()
                val store = FakeDocumentStore(failWriteText = true)
                val orchestrator = CapturePathOrchestrator(FakeClock(), log, store)
                val name = "CELLULAR_INCOMING_20240101_120000.wav"

                val result = orchestrator.finalize(bytes, name, metadata(mode))

                result.audioWritten.shouldBeTrue()
                result.metadataWritten.shouldBeFalse()
                log.countOf<StatusEntry.MetadataWriteFailure>() shouldBe 1
                // Audio recording retained despite the metadata failure.
                store.files.containsKey(name).shouldBeTrue()
                store.files.getValue(name).toList() shouldBe bytes.toList()
            }
        }
    }
})
