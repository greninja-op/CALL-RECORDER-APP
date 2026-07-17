package com.personal.unifiedrecorder.core

import com.personal.unifiedrecorder.core.audio.DspProcessor
import com.personal.unifiedrecorder.core.audio.SmartSilenceStateMachine
import com.personal.unifiedrecorder.core.audio.WavByteWriter
import com.personal.unifiedrecorder.core.consent.AcknowledgmentStore
import com.personal.unifiedrecorder.core.consent.ConsentGate
import com.personal.unifiedrecorder.core.data.DashboardFilter
import com.personal.unifiedrecorder.core.data.DurationFormat
import com.personal.unifiedrecorder.core.fake.FakeClock
import com.personal.unifiedrecorder.core.fake.FakeDocumentStore
import com.personal.unifiedrecorder.core.fake.FakeStatusLog
import com.personal.unifiedrecorder.core.logic.AccessibilityStatus
import com.personal.unifiedrecorder.core.logic.CallDetectionStatus
import com.personal.unifiedrecorder.core.logic.CallStateMachine
import com.personal.unifiedrecorder.core.logic.FilenameCodec
import com.personal.unifiedrecorder.core.model.AudioFormat
import com.personal.unifiedrecorder.core.model.CallState
import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.DashboardFilterState
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.ExecutionMode
import com.personal.unifiedrecorder.core.model.PermissionDisplay
import com.personal.unifiedrecorder.core.model.RuntimePermission
import com.personal.unifiedrecorder.core.orchestrator.CapturePathOrchestrator
import com.personal.unifiedrecorder.core.policy.PermissionStateMapper
import com.personal.unifiedrecorder.core.port.AccessibilityWindowEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/** In-memory acknowledgment store for the consent example (Req 10.1). */
private class FakeAcknowledgmentStore(private var acknowledged: Boolean = false) : AcknowledgmentStore {
    override fun isAcknowledged(): Boolean = acknowledged
    override fun setAcknowledged() { acknowledged = true }
}

class ExampleEdgeCasesTest : StringSpec({

    // --- Edge: empty PCM frame -------------------------------------------------
    "empty PCM frame yields empty DSP output, empty WAV payload, and zero average amplitude" {
        val dsp = DspProcessor()
        dsp.process(ShortArray(0), ExecutionMode.UNROOTED_LOUDSPEAKER).size shouldBe 0
        dsp.process(ShortArray(0), ExecutionMode.ROOTED_STEALTH).size shouldBe 0

        val writer = WavByteWriter(sampleRate = 44_100)
        writer.writeFrames(ShortArray(0))
        writer.dataLength shouldBe 0
        writer.totalFrames shouldBe 0
        // A valid (empty) WAV still carries the 44-byte header with a zero data length.
        writer.toByteArray().size shouldBe WavByteWriter.HEADER_SIZE
        WavByteWriter.readDataChunkLength(writer.toByteArray()) shouldBe 0

        SmartSilenceStateMachine(silenceThreshold = 1_500).averageAmplitude(ShortArray(0)) shouldBe 0
    }

    // --- Edge: single-sample frame --------------------------------------------
    "single-sample PCM frame produces exactly one mono frame of two payload bytes" {
        val writer = WavByteWriter(sampleRate = 8_000)
        writer.writeFrames(shortArrayOf(12_345))
        writer.dataLength shouldBe 2
        writer.totalFrames shouldBe 1
        WavByteWriter.readDataChunkLength(writer.toByteArray()) shouldBe 2
    }

    // --- Edge: exactly-5.0s vs just-over-5.0s silence boundary -----------------
    "Smart Silence does not pause at exactly 5.0s of silence but pauses just over 5.0s" {
        // Exactly 5.0s below threshold: streak == window, not strictly greater -> not paused.
        val atBoundary = SmartSilenceStateMachine(silenceThreshold = 1_500, silenceWindowMillis = 5_000L)
        atBoundary.process(averageAmplitude = 0, durationMillis = 5_000L)
        atBoundary.paused.shouldBeFalse()

        // Just over 5.0s: streak exceeds the window -> paused.
        val overBoundary = SmartSilenceStateMachine(silenceThreshold = 1_500, silenceWindowMillis = 5_000L)
        overBoundary.process(averageAmplitude = 0, durationMillis = 5_000L)
        overBoundary.process(averageAmplitude = 0, durationMillis = 1L)
        overBoundary.paused.shouldBeTrue()
    }

    // --- Edge: exactly-1s duplicate-event boundary (Req 1.8) -------------------
    "duplicate ACTIVE events exactly 1s apart are debounced; more than 1s apart are applied" {
        val pkg = "com.android.incallui"
        val cls = "com.android.incallui.InCallActivity"
        fun activeEvent(ts: Long) = AccessibilityWindowEvent(pkg, cls, ts, windowDismissed = false)

        val first = CallStateMachine.reduce(CallStateMachine.initial(), activeEvent(1_000L))
        first.state shouldBe CallState.ACTIVE
        first.lastAppliedAtMillis shouldBe 1_000L

        // Exactly 1000ms later (<= window): duplicate ignored, timestamp unchanged.
        val atBoundary = CallStateMachine.reduce(first, activeEvent(2_000L))
        atBoundary.lastAppliedAtMillis shouldBe 1_000L

        // More than 1000ms later: transition re-applied, timestamp advances.
        val overBoundary = CallStateMachine.reduce(first, activeEvent(2_001L))
        overBoundary.lastAppliedAtMillis shouldBe 2_001L
    }

    // --- Edge: maximum filename collision suffixing (Req 6.2) ------------------
    "filename encoding appends the next free suffix when the base and prior suffixes all collide" {
        val base = "CELLULAR_INCOMING_20240101_120000.wav"
        val existing = setOf(base, *(1..5).map { "CELLULAR_INCOMING_20240101_120000_$it.wav" }.toTypedArray())
        val name = FilenameCodec.encode(
            callType = CallType.CELLULAR,
            direction = Direction.INCOMING,
            year = 2024, month = 1, day = 1, hour = 12, minute = 0, second = 0,
            format = AudioFormat.WAV,
            existingNames = existing
        )
        name shouldBe "CELLULAR_INCOMING_20240101_120000_6.wav"
        (name in existing).shouldBeFalse()
    }

    // --- Edge: duration exactly 59:59 (Req 7.4) --------------------------------
    "duration of 3599 seconds formats as 59:59 and round-trips" {
        DurationFormat.format(3_599L) shouldBe "59:59"
        DurationFormat.parse("59:59") shouldBe 3_599L
    }

    // --- Edge: empty recording list (Req 7.9 / 9.3) ----------------------------
    "empty recording list produces the dashboard empty state and empty indexing result" {
        val result = DashboardFilter.apply(emptyList(), DashboardFilterState())
        result.entries.shouldBeEmpty()
        result.isEmpty.shouldBeTrue()

        runTest {
            val orchestrator = CapturePathOrchestrator(FakeClock(), FakeStatusLog(), FakeDocumentStore())
            orchestrator.indexRecordings().shouldBeEmpty()
        }
    }

    // --- Example: accessibility status text (Req 1.6, 1.7) ---------------------
    "accessibility enabled maps to ACTIVE detection status and disabled maps to INACTIVE" {
        AccessibilityStatus.statusFor(accessibilityEnabled = true) shouldBe CallDetectionStatus.ACTIVE
        AccessibilityStatus.statusFor(accessibilityEnabled = false) shouldBe CallDetectionStatus.INACTIVE
    }

    // --- Example: recording indicator lifecycle (Req 2.5) ----------------------
    "the capture-in-progress indicator turns on when capture begins and off after finalize" {
        runTest {
            val orchestrator = CapturePathOrchestrator(FakeClock(), FakeStatusLog(), FakeDocumentStore())
            orchestrator.captureInProgress.shouldBeFalse()

            orchestrator.handleActive(recordAudioGranted = true, mode = ExecutionMode.UNROOTED_LOUDSPEAKER)
            orchestrator.captureInProgress.shouldBeTrue()

            orchestrator.finalize(
                audioBytes = byteArrayOf(1, 2, 3),
                fileName = "CELLULAR_INCOMING_20240101_120000.wav",
                metadata = com.personal.unifiedrecorder.core.model.MetadataCompanion(
                    callType = CallType.CELLULAR,
                    direction = Direction.INCOMING,
                    identity = com.personal.unifiedrecorder.core.model.IdentityContext(),
                    timestampStartMillis = 0L,
                    durationSeconds = 1L,
                    fileExtension = "wav",
                    audioProfile = com.personal.unifiedrecorder.core.model.AudioProfile(
                        executionMode = ExecutionMode.UNROOTED_LOUDSPEAKER
                    )
                )
            )
            orchestrator.captureInProgress.shouldBeFalse()
        }
    }

    // --- Example: permission display for a single grant result (Req 8.2) -------
    "a single granted permission displays as GRANTED and a not-granted one as NOT_GRANTED" {
        val state = PermissionStateMapper.map(
            apiLevel = 30,
            granted = setOf(RuntimePermission.RECORD_AUDIO)
        )
        state.statusMap[RuntimePermission.RECORD_AUDIO] shouldBe PermissionDisplay.GRANTED
        state.statusMap[RuntimePermission.READ_PHONE_STATE] shouldBe PermissionDisplay.NOT_GRANTED
    }

    // --- Example: consent notice first-launch visibility (Req 10.1) ------------
    "on a first launch with no prior acknowledgment the consent notice is shown and recording is blocked" {
        val gate = ConsentGate(FakeAcknowledgmentStore(acknowledged = false))
        gate.shouldShowNoticeOnLaunch().shouldBeTrue()
        gate.recordingPermitted().shouldBeFalse()

        val acknowledgedGate = ConsentGate(FakeAcknowledgmentStore(acknowledged = true))
        acknowledgedGate.shouldShowNoticeOnLaunch().shouldBeFalse()
    }
})
