package com.personal.unifiedrecorder.core.audio

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

private const val SS_THRESHOLD = 1_500
private const val SS_WINDOW_MS = 5_000L
private const val SAMPLE_RATE = 8_000

/**
 * A capture frame: silence, DC-offset, full-scale, or noise, of varying length.
 * Silent-leaning frames let the Smart Silence machine cross its 5.0s window and
 * pause, so the writer is driven through arbitrary pause/resume sequences.
 */
private fun captureFrameArb(): Arb<ShortArray> = arbitrary { rs ->
    val len = Arb.int(1..1_600).sample(rs).value
    when (Arb.int(0..3).sample(rs).value) {
        0 -> ShortArray(len)                                   // silence (below threshold)
        1 -> {                                                 // low DC offset (may be below threshold)
            val dc = Arb.int(0..2_500).sample(rs).value.toShort()
            ShortArray(len) { dc }
        }
        2 -> ShortArray(len) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE } // full-scale (audible)
        else -> ShortArray(len) { Arb.int(Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()).sample(rs).value.toShort() }
    }
}

class WavPauseResumePropertyTest : StringSpec({

    // Feature: unified-call-recorder, Property 18: WAV output remains structurally valid across arbitrary pause/resume sequences
    // Validates: Requirements 5.3
    "Property 18: WAV stays valid across Smart Silence pause/resume — data length is a whole multiple of frame size and equals bytes written" {
        checkAll(200, Arb.list(captureFrameArb(), range = 0..40)) { frames ->
            val writer = WavByteWriter(sampleRate = SAMPLE_RATE, channels = 1)
            val silence = SmartSilenceStateMachine(SS_THRESHOLD, SS_WINDOW_MS)

            var bytesWrittenExpected = 0
            for (frame in frames) {
                val durationMs = frame.size.toLong() * 1_000L / SAMPLE_RATE
                val amp = silence.averageAmplitude(frame)
                if (silence.process(amp, durationMs)) {
                    writer.writeFrames(frame)
                    bytesWrittenExpected += frame.size * writer.bytesPerSample
                }
            }

            // Data-chunk length equals the bytes actually serialized ...
            writer.dataLength shouldBe bytesWrittenExpected
            // ... and is a whole multiple of the frame size (never corrupted by pausing).
            (writer.dataLength % writer.frameSize) shouldBe 0
            // The emitted header reports the same data length.
            WavByteWriter.readDataChunkLength(writer.toByteArray()) shouldBe bytesWrittenExpected
        }
    }
})
