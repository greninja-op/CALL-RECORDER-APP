package com.personal.unifiedrecorder.core.audio

import com.personal.unifiedrecorder.core.model.ExecutionMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlin.math.abs
import kotlin.math.sqrt

/** Non-empty PCM frame generator: silence, full-scale, DC-offset, and noise. */
private fun nonEmptyPcmArb(maxLen: Int = 1_500): Arb<ShortArray> = arbitrary { rs ->
    val len = Arb.int(1..maxLen).sample(rs).value
    when (Arb.int(0..3).sample(rs).value) {
        0 -> ShortArray(len)                                   // silence
        1 -> ShortArray(len) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE } // full-scale alternating
        2 -> {                                                 // DC offset
            val dc = Arb.int(-25_000..25_000).sample(rs).value.toShort()
            ShortArray(len) { dc }
        }
        else -> ShortArray(len) { Arb.int(Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()).sample(rs).value.toShort() }
    }
}

private fun rms(a: ShortArray): Double {
    if (a.isEmpty()) return 0.0
    var sum = 0.0
    for (s in a) { val v = s.toDouble(); sum += v * v }
    return sqrt(sum / a.size)
}

/** High-frequency energy proxy: sum of squared consecutive differences. */
private fun hfEnergy(a: ShortArray): Double {
    var sum = 0.0
    for (i in 1 until a.size) {
        val d = a[i].toDouble() - a[i - 1].toDouble()
        sum += d * d
    }
    return sum
}

class DspProcessorPropertyTest : StringSpec({

    // Feature: unified-call-recorder, Property 15: DSP output stays in bounds and normalizes level
    // Validates: Requirements 4.1
    "Property 15: DSP clamps to 16-bit, AGC moves level toward target, low-pass never raises HF energy" {
        val target = 8_000.0
        val dsp = DspProcessor(targetLevel = target)
        checkAll(200, nonEmptyPcmArb()) { input ->
            val out = dsp.process(input, ExecutionMode.UNROOTED_LOUDSPEAKER)

            // (1) Every output sample stays within the 16-bit range.
            out.all { it.toInt() in DspProcessor.SAMPLE_MIN..DspProcessor.SAMPLE_MAX }.shouldBeTrue()

            // (2) AGC-applied level moves toward the configured target (never further away).
            val agc = dsp.applyAgc(input)
            val inLevel = rms(input)
            val outLevel = rms(agc)
            (abs(outLevel - target) <= abs(inLevel - target) + 1.0).shouldBeTrue()

            // (3) The low-pass stage does not increase high-frequency energy vs its input.
            val lp = dsp.applyLowPass(input)
            val inHf = hfEnergy(input)
            (hfEnergy(lp) <= inHf + inHf * 1e-6 + 4_096.0).shouldBeTrue()
        }
    }

    // Feature: unified-call-recorder, Property 16: Rooted stealth bypasses DSP (identity)
    // Validates: Requirements 4.3
    "Property 16: ROOTED_STEALTH returns the raw samples unchanged" {
        val dsp = DspProcessor()
        checkAll(200, nonEmptyPcmArb()) { input ->
            val out = dsp.process(input, ExecutionMode.ROOTED_STEALTH)
            out.contentEquals(input) shouldBe true
        }
    }
})
