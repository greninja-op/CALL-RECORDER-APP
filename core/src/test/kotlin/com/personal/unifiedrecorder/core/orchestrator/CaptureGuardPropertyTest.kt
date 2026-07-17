package com.personal.unifiedrecorder.core.orchestrator

import com.personal.unifiedrecorder.core.fake.FakeClock
import com.personal.unifiedrecorder.core.fake.FakeDocumentStore
import com.personal.unifiedrecorder.core.fake.FakeStatusLog
import com.personal.unifiedrecorder.core.model.CallState
import com.personal.unifiedrecorder.core.model.ExecutionMode
import com.personal.unifiedrecorder.core.model.MetadataCompanion
import com.personal.unifiedrecorder.core.model.RuntimePermission
import com.personal.unifiedrecorder.core.model.StatusEntry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest

private val CALL_STATES = Arb.of(CallState.entries.toList())
private val MODES = Arb.of(ExecutionMode.entries.toList())

private fun audioBytesArb(): Arb<ByteArray> = Arb.byteArray(Arb.int(1..64), Arb.byte())

private fun metadataFor(mode: ExecutionMode): MetadataCompanion =
    MetadataCompanion(
        callType = com.personal.unifiedrecorder.core.model.CallType.CELLULAR,
        direction = com.personal.unifiedrecorder.core.model.Direction.INCOMING,
        identity = com.personal.unifiedrecorder.core.model.IdentityContext(),
        timestampStartMillis = 1_000L,
        durationSeconds = 42L,
        fileExtension = "wav",
        audioProfile = com.personal.unifiedrecorder.core.model.AudioProfile(executionMode = mode)
    )

class CaptureGuardPropertyTest : StringSpec({

    // Feature: unified-call-recorder, Property 4: Capture-begin guard
    // Validates: Requirements 2.1, 2.4, 2.7
    "Property 4: begin iff ACTIVE, granted, and not in progress; continue when already running; skip + PermissionMissing when ungranted" {
        checkAll(200, CALL_STATES, Arb.boolean(), Arb.boolean(), MODES) { state, granted, inProgress, mode ->
            val expected = when {
                state != CallState.ACTIVE -> CaptureBeginDecision.IGNORE
                inProgress -> CaptureBeginDecision.CONTINUE_EXISTING
                !granted -> CaptureBeginDecision.SKIP_PERMISSION_MISSING
                else -> CaptureBeginDecision.BEGIN_NEW
            }
            // The pure guard is the "if and only if" core of the property.
            CapturePathOrchestrator.decideBegin(state, granted, inProgress) shouldBe expected

            // Side effects only apply to ACTIVE; drive the orchestrator to verify them.
            if (state == CallState.ACTIVE) {
                val log = FakeStatusLog()
                val orchestrator = CapturePathOrchestrator(FakeClock(), log, FakeDocumentStore())
                if (inProgress) {
                    // Establish an in-progress capture first, then re-enter ACTIVE.
                    orchestrator.handleActive(recordAudioGranted = true, mode = mode)
                    val startedBefore = log.countOf<StatusEntry.CaptureStarted>()
                    orchestrator.handleActive(recordAudioGranted = granted, mode = mode) shouldBe
                        CaptureBeginDecision.CONTINUE_EXISTING
                    // Continuing must not start a second capture.
                    log.countOf<StatusEntry.CaptureStarted>() shouldBe startedBefore
                    orchestrator.captureInProgress.shouldBeTrue()
                } else {
                    val decision = orchestrator.handleActive(recordAudioGranted = granted, mode = mode)
                    decision shouldBe expected
                    if (granted) {
                        orchestrator.captureInProgress.shouldBeTrue()
                        log.countOf<StatusEntry.CaptureStarted>() shouldBe 1
                    } else {
                        orchestrator.captureInProgress shouldBe false
                        log.countOf<StatusEntry.PermissionMissing>() shouldBe 1
                        log.entries.filterIsInstance<StatusEntry.PermissionMissing>()
                            .single().permission shouldBe RuntimePermission.RECORD_AUDIO
                    }
                }
            }
        }
    }

    // Feature: unified-call-recorder, Property 5: Abnormal finalize preserves the captured portion
    // Validates: Requirements 2.3
    "Property 5: when finalize fails with captured audio, the portion is retained as a playable file and CaptureStopped(abnormal=true) is logged" {
        checkAll(100, audioBytesArb(), MODES) { bytes, mode ->
            val log = FakeStatusLog()
            // failClose retains the fully-written buffer but fails the finalize (abnormal stop).
            val store = FakeDocumentStore(failClose = true)
            val orchestrator = CapturePathOrchestrator(FakeClock(), log, store)
            val name = "CELLULAR_INCOMING_20240101_120000.wav"

            val result = orchestrator.finalize(bytes, name, metadataFor(mode))

            result.audioWritten shouldBe false
            result.abnormal shouldBe true
            result.abortedBeforeWrite shouldBe false
            // Captured portion retained (not discarded).
            store.files.containsKey(name).shouldBeTrue()
            store.files.getValue(name).toList() shouldBe bytes.toList()
            // Abnormal-stop status recorded.
            log.countOf<StatusEntry.CaptureStopped>() shouldBe 1
            log.entries.filterIsInstance<StatusEntry.CaptureStopped>().single().abnormal.shouldBeTrue()
        }
    }

    // Feature: unified-call-recorder, Property 6: Capture-begin/continue failure discards the partial
    // Validates: Requirements 2.6
    "Property 6: a begin/continue failure stops the attempt, discards any partial, and logs a capture-failure status" {
        checkAll(100, Arb.boolean(), MODES) { hadBegun, mode ->
            runTest {
                val log = FakeStatusLog()
                val store = FakeDocumentStore()
                val orchestrator = CapturePathOrchestrator(FakeClock(), log, store)
                if (hadBegun) orchestrator.handleActive(recordAudioGranted = true, mode = mode)

                orchestrator.handleCaptureFailure()

                // Nothing persisted: the partial recording is discarded.
                store.files.isEmpty().shouldBeTrue()
                store.texts.isEmpty().shouldBeTrue()
                orchestrator.captureInProgress shouldBe false
                // A capture-failure (abnormal stop) status is recorded.
                log.countOf<StatusEntry.CaptureStopped>() shouldBe 1
                log.entries.filterIsInstance<StatusEntry.CaptureStopped>().single().abnormal.shouldBeTrue()
            }
        }
    }
})
