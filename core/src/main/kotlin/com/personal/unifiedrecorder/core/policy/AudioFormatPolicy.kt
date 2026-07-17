package com.personal.unifiedrecorder.core.policy

import com.personal.unifiedrecorder.core.model.AudioFormat
import com.personal.unifiedrecorder.core.model.StatusEntry

/**
 * Chooses the on-disk audio format and the fallback format when encoding fails
 * (Requirements 6.3, 6.4).
 *
 * Selection rule (6.3): WAV is forced whenever the DSP pipeline or the Smart Silence
 * optimization is active (both require a raw PCM path written as WAV). Otherwise the
 * Application-configured format is used, defaulting to AAC when none is configured.
 *
 * Fallback rule (6.4): when the chosen format fails to encode, a distinct supported
 * format that is not among the failed set is selected and a [StatusEntry.FormatFallback]
 * is produced for the caller to log.
 */
object AudioFormatPolicy {

    /** The complete set of supported formats, in fallback-preference order. */
    private val SUPPORTED: List<AudioFormat> = listOf(AudioFormat.WAV, AudioFormat.MP4, AudioFormat.AAC)

    /**
     * Select the encoding format for a call.
     *
     * @param dspActive whether the real-time DSP pipeline is active for this call.
     * @param smartSilenceActive whether Smart Silence optimization is active for this call.
     * @param configuredFormat the Application-configured format, or null when unset.
     * @return [AudioFormat.WAV] when [dspActive] or [smartSilenceActive] is true; otherwise
     *         [configuredFormat], defaulting to [AudioFormat.AAC] when null.
     */
    fun select(
        dspActive: Boolean,
        smartSilenceActive: Boolean,
        configuredFormat: AudioFormat?
    ): AudioFormat =
        if (dspActive || smartSilenceActive) AudioFormat.WAV
        else configuredFormat ?: AudioFormat.AAC

    /** The outcome of a format fallback: the chosen format and the status entry to log. */
    data class FallbackResult(
        val format: AudioFormat,
        val statusEntry: StatusEntry.FormatFallback
    )

    /**
     * Choose a fallback format after one or more encoding failures.
     *
     * @param from the format that just failed (recorded as the `from` of the status entry).
     * @param failedFormats the set of formats already known to fail (should include [from]).
     * @param timestampMillis timestamp for the produced status entry.
     * @return a [FallbackResult] whose [FallbackResult.format] is a supported format not present
     *         in [failedFormats], or null when every supported format has failed.
     */
    fun fallback(
        from: AudioFormat,
        failedFormats: Set<AudioFormat>,
        timestampMillis: Long
    ): FallbackResult? {
        val candidate = SUPPORTED.firstOrNull { it !in failedFormats } ?: return null
        return FallbackResult(
            format = candidate,
            statusEntry = StatusEntry.FormatFallback(
                timestampMillis = timestampMillis,
                from = from,
                to = candidate
            )
        )
    }
}
