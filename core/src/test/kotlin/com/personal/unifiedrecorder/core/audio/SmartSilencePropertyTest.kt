package com.personal.unifiedrecorder.core.audio

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

private const val THRESHOLD = 1_500
private const val WINDOW_MS = 5_000L

/** A single (averageAmplitude, durationMs) timeline entry. */
private fun pairArb(): Arb<Pair<Int, Long>> = arbitrary { rs ->
    val amp = Arb.int(0..3_000).sample(rs).value
    val dur = Arb.long(0L..2_500L).sample(rs).value
    amp to dur
}

/**
 * Amplitude-over-time timeline generator: a list of (averageAmplitude, durationMs)
 * pairs. Amplitudes straddle the threshold and durations are large enough that a
 * continuous silent run can exceed the 5.0s window over a few frames.
 */
private fun timelineArb(): Arb<List<Pair<Int, Long>>> =
    Arb.list(pairArb(), range = 0..40)

class SmartSilencePropertyTest : StringSpec({

    // Feature: unified-call-recorder, Property 17: Smart Silence pauses only after more than five continuous seconds below threshold
    // Validates: Requirements 5.2, 5.3
    "Property 17: paused iff the continuous below-threshold streak exceeds 5.0s; resumes at/above threshold" {
        checkAll(200, timelineArb()) { timeline ->
            val sm = SmartSilenceStateMachine(THRESHOLD, WINDOW_MS)
            var streak = 0L
            for ((amp, dur) in timeline) {
                sm.process(amp, dur)
                if (amp >= THRESHOLD) {
                    streak = 0L
                } else {
                    streak += dur
                }
                sm.paused shouldBe (streak > WINDOW_MS)
            }
        }
    }

    // Feature: unified-call-recorder, Property 19: Skipped-silence accounting is exact
    // Validates: Requirements 5.4
    "Property 19: skipped duration equals the total time serialization was paused" {
        checkAll(200, timelineArb()) { timeline ->
            val sm = SmartSilenceStateMachine(THRESHOLD, WINDOW_MS)
            var streak = 0L
            var expectedSkipped = 0L
            for ((amp, dur) in timeline) {
                sm.process(amp, dur)
                if (amp >= THRESHOLD) {
                    streak = 0L
                } else {
                    streak += dur
                    if (streak > WINDOW_MS) expectedSkipped += dur
                }
            }
            sm.skippedMillis shouldBe expectedSkipped
        }
    }
})
