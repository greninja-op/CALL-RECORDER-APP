package com.personal.unifiedrecorder.core.audio

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 * PCM frame generator covering silence, full-scale, DC-offset, and random noise
 * so the WAV writer is exercised across representative sample content.
 */
private fun pcmFrameArb(minLen: Int = 0, maxLen: Int = 2_000): Arb<ShortArray> = arbitrary { rs ->
    val len = Arb.int(minLen..maxLen).sample(rs).value
    when (Arb.int(0..3).sample(rs).value) {
        0 -> ShortArray(len)                                   // silence
        1 -> ShortArray(len) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE } // full-scale
        2 -> {                                                 // DC offset
            val dc = Arb.int(-20_000..20_000).sample(rs).value.toShort()
            ShortArray(len) { dc }
        }
        else -> ShortArray(len) { Arb.int(Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()).sample(rs).value.toShort() }
    }
}

class WavByteWriterPropertyTest : StringSpec({

    // Feature: unified-call-recorder, Property 7: Finalized WAV duration equals captured length
    // Validates: Requirements 2.2
    "Property 7: reported duration equals N/R and header data length equals PCM payload byte count" {
        val sampleRateArb = Arb.of(8_000, 11_025, 16_000, 22_050, 32_000, 44_100, 48_000)
        checkAll(200, sampleRateArb, pcmFrameArb()) { sampleRate, samples ->
            val writer = WavByteWriter(sampleRate = sampleRate, channels = 1)
            writer.writeFrames(samples)

            val expectedPayloadBytes = samples.size * 2
            // Header's data-chunk length equals the PCM payload byte count.
            writer.dataLength shouldBe expectedPayloadBytes
            WavByteWriter.readDataChunkLength(writer.toByteArray()) shouldBe expectedPayloadBytes

            // Reported duration = N frames / R (mono => frames == sample count).
            writer.totalFrames shouldBe samples.size
            writer.durationSeconds shouldBeExactly (samples.size.toDouble() / sampleRate)
        }
    }
})
