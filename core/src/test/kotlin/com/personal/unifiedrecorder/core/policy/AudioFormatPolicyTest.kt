package com.personal.unifiedrecorder.core.policy

import com.personal.unifiedrecorder.core.model.AudioFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

class AudioFormatPolicyTest : StringSpec({

    // A configured format may be one of the three supported formats or unset (null).
    val configuredFormatArb: Arb<AudioFormat?> =
        Arb.element(AudioFormat.WAV, AudioFormat.MP4, AudioFormat.AAC, null)

    // All proper subsets of {WAV, MP4, AAC} (i.e. every subset except the full set), so that
    // at least one supported format always remains available for the fallback.
    val allFormats = listOf(AudioFormat.WAV, AudioFormat.MP4, AudioFormat.AAC)
    val properSubsets: List<Set<AudioFormat>> =
        (0 until (1 shl allFormats.size))
            .map { mask -> allFormats.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet() }
            .filter { it.size < allFormats.size }
    val failedSetArb: Arb<Set<AudioFormat>> = Arb.element(properSubsets)

    // Feature: unified-call-recorder, Property 21: Audio format selection policy
    // Validates: Requirements 6.3
    "Property 21: WAV when DSP or Smart Silence active; else configured, defaulting to AAC" {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.boolean(),
            Arb.boolean(),
            configuredFormatArb
        ) { dspActive, smartSilenceActive, configured ->
            val expected = when {
                dspActive || smartSilenceActive -> AudioFormat.WAV
                configured != null -> configured
                else -> AudioFormat.AAC
            }
            AudioFormatPolicy.select(dspActive, smartSilenceActive, configured) shouldBe expected
        }
    }

    // Feature: unified-call-recorder, Property 22: Format fallback selects a distinct supported format
    // Validates: Requirements 6.4
    "Property 22: fallback selects a supported format outside the failed set and logs FormatFallback" {
        checkAll(
            PropTestConfig(iterations = 100),
            failedSetArb,
            Arb.element(AudioFormat.WAV, AudioFormat.MP4, AudioFormat.AAC),
            Arb.long(0L, Long.MAX_VALUE)
        ) { failedFormats, from, ts ->
            val result = AudioFormatPolicy.fallback(from, failedFormats, ts)
            result.shouldNotBeNull()
            // Chosen format is supported and distinct from every failed format.
            (result.format in failedFormats) shouldBe false
            // A FormatFallback status entry is produced pointing at the chosen format.
            result.statusEntry.to shouldBe result.format
            result.statusEntry.from shouldBe from
            result.statusEntry.timestampMillis shouldBe ts
        }
    }
})
