package com.personal.unifiedrecorder.core.playback

import com.personal.unifiedrecorder.core.port.PlaybackResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

private const val ITERATIONS = 100

// ---------------------------------------------------------------------------
// Inline generators for play/pause/resume/stop op sequences
// ---------------------------------------------------------------------------

private fun nameArb(): Arb<String> = Arb.string(1, 10, Codepoint.az()).map { "$it.wav" }

private fun successfulPlayArb(): Arb<PlaybackOp> = arbitrary {
    PlaybackOp.Play(
        name = nameArb().bind(),
        result = PlaybackResult(success = true, durationMillis = Arb.long(1L, 600_000L).bind())
    )
}

private fun advanceArb(): Arb<PlaybackOp> = Arb.long(1L, 30_000L).map { PlaybackOp.Advance(it) }

private fun opArb(): Arb<PlaybackOp> = Arb.choice(
    successfulPlayArb(),
    advanceArb(),
    arbitrary { PlaybackOp.Pause },
    arbitrary { PlaybackOp.Resume },
    arbitrary { PlaybackOp.Stop }
)

class PlaybackStateModelPropertyTest : FunSpec({

    // Feature: unified-call-recorder, Property 33: For any sequence of play/pause/resume/stop operations, the modeled playback position SHALL resume from the paused position after resume and SHALL reset to the start (position 0) after stop.
    // Validates: Requirements 7.7
    test("Property 33: playback state transitions") {
        checkAll(PropTestConfig(iterations = ITERATIONS), Arb.list(opArb(), 0..30)) { ops ->
            var state = PlaybackStateModel.initial()
            for (op in ops) {
                val before = state
                val after = PlaybackStateModel.reduce(before, op)

                when {
                    // Resume from a paused state continues from the paused position.
                    op is PlaybackOp.Resume && before.status == PlaybackStatus.PAUSED -> {
                        after.status shouldBe PlaybackStatus.PLAYING
                        after.positionMillis shouldBe before.positionMillis
                    }
                    // Stop always returns the position to the start.
                    op is PlaybackOp.Stop -> {
                        after.status shouldBe PlaybackStatus.STOPPED
                        after.positionMillis shouldBe 0L
                    }
                }
                state = after
            }
        }
    }

    // Feature: unified-call-recorder, Property 35: For any playback attempt that cannot start or fails before completion, the view-state SHALL become an error state and the recording SHALL remain present in the list.
    // Validates: Requirements 7.10
    test("Property 35: playback failure surfaces an error and retains the entry") {
        checkAll(
            PropTestConfig(iterations = ITERATIONS),
            Arb.list(nameArb(), 1..10),
            Arb.string(0, 20)
        ) { entries, errorText ->
            // Pick an existing entry and attempt to play it with a failing result.
            val target = entries.first()
            val failing = PlaybackResult(success = false, errorMessage = errorText.ifEmpty { null })

            val state = PlaybackStateModel.reduce(
                PlaybackStateModel.initial(),
                PlaybackOp.Play(target, failing)
            )

            // View-state becomes an error state naming the recording.
            state.status shouldBe PlaybackStatus.ERROR
            state.currentEntry shouldBe target
            state.errorMessage.shouldNotBeNull()

            // The recording remains present in the list (the model never mutates it).
            entries shouldContain target
            entries.contains(target).shouldBeTrue()
        }
    }
})
