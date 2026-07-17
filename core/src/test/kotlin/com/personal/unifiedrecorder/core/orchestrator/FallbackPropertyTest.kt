package com.personal.unifiedrecorder.core.orchestrator

import com.personal.unifiedrecorder.core.fake.FakeClock
import com.personal.unifiedrecorder.core.fake.FakeDocumentStore
import com.personal.unifiedrecorder.core.fake.FakeStatusLog
import com.personal.unifiedrecorder.core.model.StatusEntry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

private const val THRESHOLD = 1_500

private fun newOrchestrator(log: FakeStatusLog): CapturePathOrchestrator =
    CapturePathOrchestrator(FakeClock(autoAdvanceMillis = 1L), log, FakeDocumentStore())

/** An audible path resolves immediately (one loud short frame). */
private fun audiblePath(): PathScript =
    PathScript(configSucceeds = true, timeline = listOf(AmplitudeSample(THRESHOLD, 20L)))

/** A failing path that configures but never produces audio (config failure, consumes no time). */
private fun configFailPath(): PathScript = PathScript(configSucceeds = false)

/** A silent path that fails over after >= 3s of continuous silence. */
private fun silentPath(): PathScript =
    PathScript(configSucceeds = true, timeline = listOf(AmplitudeSample(0, 3_000L)))

private fun amplitudeSampleArb(): Arb<AmplitudeSample> = arbitrary { rs ->
    val amp = Arb.int(0..3_000).sample(rs).value
    val dur = Arb.long(0L..2_000L).sample(rs).value
    AmplitudeSample(amp, dur)
}

class FallbackPropertyTest : StringSpec({

    // Feature: unified-call-recorder, Property 10: Fallback retry is bounded and fully logged
    // Validates: Requirements 3.4
    "Property 10: at most 3 attempts, exactly one PathAttempt per attempt, and stop on the first success" {
        checkAll(200, Arb.list(Arb.boolean(), 0..8)) { outcomes ->
            val log = FakeStatusLog()
            val orchestrator = newOrchestrator(log)
            // Map each outcome to a path: success -> audible, failure -> config failure (0ms).
            val scripts = outcomes.map { success -> if (success) audiblePath() else configFailPath() }

            val result = orchestrator.runFallback(scripts, THRESHOLD)

            // Expected attempts: iterate up to 3, stopping on the first success.
            var expectedAttempts = 0
            var expectedSuccess = false
            for (success in outcomes) {
                if (expectedAttempts >= CapturePathOrchestrator.MAX_ATTEMPTS) break
                expectedAttempts++
                if (success) { expectedSuccess = true; break }
            }

            result.attempts shouldBe expectedAttempts
            result.succeeded shouldBe expectedSuccess
            result.attempts shouldBeLessThanOrEqual CapturePathOrchestrator.MAX_ATTEMPTS

            val attempts = log.entries.filterIsInstance<StatusEntry.PathAttempt>()
            // Exactly one PathAttempt per attempt, numbered 1..attempts in order.
            attempts.size shouldBe expectedAttempts
            attempts.forEachIndexed { i, entry ->
                entry.attempt shouldBe (i + 1)
                entry.success shouldBe outcomes[i]
            }
            // Stop-on-first-success: success appears only as the last attempt, if at all.
            log.countOf<StatusEntry.PathSucceeded>() shouldBe if (expectedSuccess) 1 else 0
            log.countOf<StatusEntry.CaptureUnestablished>() shouldBe if (expectedSuccess) 0 else 1
        }
    }

    // Feature: unified-call-recorder, Property 11: Continuous silence triggers path failover
    // Validates: Requirements 3.5
    "Property 11: a configured path is treated as failed via silence failover iff it is continuously silent for at least 3 seconds before any audible frame" {
        checkAll(200, Arb.list(amplitudeSampleArb(), 0..40)) { timeline ->
            val orchestrator = CapturePathOrchestrator(FakeClock(), FakeStatusLog(), FakeDocumentStore())
            val evaluation = orchestrator.evaluatePath(PathScript(configSucceeds = true, timeline = timeline), THRESHOLD)

            // Reference walk: audible wins if a loud frame precedes a 3s continuous-silence run.
            var streak = 0L
            var refAudible = false
            var refFailover = false
            for (sample in timeline) {
                if (sample.averageAmplitude >= THRESHOLD) { refAudible = true; break }
                streak += sample.durationMillis
                if (streak >= CapturePathOrchestrator.SILENCE_FAILOVER_MILLIS) { refFailover = true; break }
            }

            evaluation.audibleDetected shouldBe refAudible
            evaluation.silentFailover shouldBe refFailover
        }
    }

    // Feature: unified-call-recorder, Property 12: Audible stream logs path success exactly once
    // Validates: Requirements 3.6
    "Property 12: a capture path producing an audible stream logs exactly one PathSucceeded" {
        checkAll(100, Arb.int(0..2), Arb.list(Arb.boolean(), 0..4)) { leadingFailures, trailing ->
            val log = FakeStatusLog()
            val orchestrator = newOrchestrator(log)
            // Some leading (non-audible) attempts within the bound, then a guaranteed audible path.
            val scripts = buildList {
                repeat(leadingFailures) { add(configFailPath()) }
                add(audiblePath())
                trailing.forEach { add(if (it) audiblePath() else silentPath()) }
            }

            orchestrator.runFallback(scripts, THRESHOLD)

            log.countOf<StatusEntry.PathSucceeded>() shouldBe 1
            log.countOf<StatusEntry.CaptureUnestablished>() shouldBe 0
        }
    }

    // Feature: unified-call-recorder, Property 13: Exhausted paths log capture-unestablished with append-only status history
    // Validates: Requirements 3.7
    "Property 13: when all paths fail within the budget, CaptureUnestablished is appended and every prior status entry is retained" {
        checkAll(200, Arb.list(Arb.boolean(), 1..6)) { silentChoices ->
            val log = FakeStatusLog()
            val orchestrator = newOrchestrator(log)
            // Seed prior history to verify the log is only ever appended to.
            log.record(StatusEntry.CaptureStarted(0L, com.personal.unifiedrecorder.core.model.ExecutionMode.UNROOTED_LOUDSPEAKER))
            log.record(StatusEntry.PathAttempt(0L, 1, false))
            val before = log.entries

            // All-failing paths: either a config failure or a >=3s silent failover.
            val scripts = silentChoices.map { silent -> if (silent) silentPath() else configFailPath() }
            orchestrator.runFallback(scripts, THRESHOLD)

            log.countOf<StatusEntry.PathSucceeded>() shouldBe 0
            log.countOf<StatusEntry.CaptureUnestablished>() shouldBe 1
            // Append-only: prior entries retained verbatim as the prefix of the log.
            log.entries.subList(0, before.size) shouldBe before
            log.entries.size shouldBeGreaterThanOrEqual before.size
        }
    }
})
