package com.personal.unifiedrecorder.core.audio

import com.personal.unifiedrecorder.core.model.ExecutionMode
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Real-time DSP for the unrooted loudspeaker path: Automatic Gain Control (AGC)
 * followed by a single-pole low-pass filter, operating on 16-bit PCM frames.
 *
 * - **AGC** drives the frame's signal level toward [targetLevel] using a gain
 *   bounded by [[minGain], [maxGain]]. Because the gain is `target / level`, the
 *   resulting level moves *toward* the target and never diverges away from it.
 * - **Clamp** keeps every produced sample within the 16-bit range
 *   `[-32768, 32767]`.
 * - **Low-pass** is a first-order IIR (`y[n] = y[n-1] + alpha*(x[n]-y[n-1])`) with
 *   `0 < alpha < 1`; its magnitude response never exceeds unity, so it never
 *   increases high-frequency energy relative to its input.
 *
 * When the [ExecutionMode] is [ExecutionMode.ROOTED_STEALTH] the pipeline is
 * bypassed entirely and the raw samples are returned unchanged (identity).
 *
 * _Requirements: 4.1, 4.3, 4.4_
 */
class DspProcessor(
    private val targetLevel: Double = 8_000.0,
    private val maxGain: Double = 8.0,
    private val minGain: Double = 0.05,
    private val lowPassAlpha: Double = 0.2,
    private val dspFilterId: String = "agc_lowpass_v1"
) {
    init {
        require(targetLevel > 0.0) { "targetLevel must be positive" }
        require(maxGain >= 1.0) { "maxGain must be >= 1.0" }
        require(minGain in 0.0..1.0) { "minGain must be within (0, 1]" }
        require(lowPassAlpha > 0.0 && lowPassAlpha < 1.0) { "lowPassAlpha must be in (0, 1)" }
    }

    /** Identifier recorded in the Metadata_Companion audio_profile (Req 4.4). */
    fun filterId(): String = dspFilterId

    /**
     * Applies the full pipeline for the given [mode]. For [ExecutionMode.ROOTED_STEALTH]
     * the input is returned unchanged (a content-identical copy). Otherwise AGC is
     * applied, then the low-pass filter, with 16-bit clamping throughout.
     */
    fun process(input: ShortArray, mode: ExecutionMode): ShortArray {
        if (mode == ExecutionMode.ROOTED_STEALTH) return input.copyOf()
        return applyLowPass(applyAgc(input))
    }

    /** Automatic Gain Control toward [targetLevel] with a bounded gain and 16-bit clamp. */
    fun applyAgc(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        val level = rms(input)
        val gain = if (level <= 0.0) 1.0 else (targetLevel / level).coerceIn(minGain, maxGain)
        return ShortArray(input.size) { i -> clampToShort((input[i] * gain).roundToInt()) }
    }

    /** Single-pole low-pass filter with 16-bit clamp. */
    fun applyLowPass(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        val out = ShortArray(input.size)
        var y = input[0].toDouble()
        for (i in input.indices) {
            y += lowPassAlpha * (input[i].toDouble() - y)
            out[i] = clampToShort(y.roundToInt())
        }
        return out
    }

    private fun rms(a: ShortArray): Double {
        var sum = 0.0
        for (s in a) {
            val v = s.toDouble()
            sum += v * v
        }
        return sqrt(sum / a.size)
    }

    companion object {
        const val SAMPLE_MIN: Int = -32768
        const val SAMPLE_MAX: Int = 32767

        fun clampToShort(v: Int): Short = v.coerceIn(SAMPLE_MIN, SAMPLE_MAX).toShort()
    }
}
