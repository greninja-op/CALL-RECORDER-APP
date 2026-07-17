package com.personal.unifiedrecorder.core.audio

import kotlin.math.abs

/**
 * Deterministic amplitude/frame-count gate for the Smart Silence storage
 * optimization. Serialization is paused only after the average frame amplitude
 * stays *strictly below* [silenceThreshold] for a continuous period *exceeding*
 * [silenceWindowMillis] (5.0 seconds by default); it resumes as soon as the
 * amplitude rises to or above the threshold. The machine also accumulates the
 * total skipped (paused) duration.
 *
 * Time is driven entirely by the per-frame durations passed to [process] — the
 * machine never reads a real clock, which keeps it fully testable on the JVM.
 *
 * _Requirements: 5.1, 5.2, 5.3, 5.4_
 */
class SmartSilenceStateMachine(
    private val silenceThreshold: Int,
    private val silenceWindowMillis: Long = 5_000L
) {
    /** True while serialization is currently paused (skipping frames). */
    var paused: Boolean = false
        private set

    private var belowStreakMillis: Long = 0L

    /** Total duration, in milliseconds, for which serialization has been paused. */
    var skippedMillis: Long = 0L
        private set

    /** Total skipped silent seconds recorded into the audio_profile (Req 5.4). */
    val skippedSilentSeconds: Int get() = (skippedMillis / 1_000L).toInt()

    /**
     * Processes one frame described by its [averageAmplitude] and [durationMillis].
     *
     * @return `true` if the frame should be serialized (written), `false` if it is
     *   skipped because serialization is paused.
     */
    fun process(averageAmplitude: Int, durationMillis: Long): Boolean {
        require(durationMillis >= 0L) { "durationMillis must be >= 0, was $durationMillis" }
        if (averageAmplitude >= silenceThreshold) {
            // Amplitude rose to/above threshold: resume immediately.
            paused = false
            belowStreakMillis = 0L
            return true
        }
        // Strictly below the threshold: extend the continuous-silence streak.
        belowStreakMillis += durationMillis
        paused = belowStreakMillis > silenceWindowMillis
        if (paused) {
            skippedMillis += durationMillis
            return false
        }
        return true
    }

    /** Computes the mean absolute amplitude of a PCM frame. */
    fun averageAmplitude(frame: ShortArray): Int {
        if (frame.isEmpty()) return 0
        var sum = 0L
        for (s in frame) sum += abs(s.toInt()).toLong()
        return (sum / frame.size).toInt()
    }
}
