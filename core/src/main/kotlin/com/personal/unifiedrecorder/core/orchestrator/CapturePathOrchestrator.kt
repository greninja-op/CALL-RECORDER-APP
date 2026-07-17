package com.personal.unifiedrecorder.core.orchestrator

import com.personal.unifiedrecorder.core.data.MetadataCodec
import com.personal.unifiedrecorder.core.logic.FilenameCodec
import com.personal.unifiedrecorder.core.model.AudioProfile
import com.personal.unifiedrecorder.core.model.CallState
import com.personal.unifiedrecorder.core.model.ExecutionMode
import com.personal.unifiedrecorder.core.model.MetadataCompanion
import com.personal.unifiedrecorder.core.model.RecordingEntry
import com.personal.unifiedrecorder.core.model.RuntimePermission
import com.personal.unifiedrecorder.core.model.StatusEntry
import com.personal.unifiedrecorder.core.port.Clock
import com.personal.unifiedrecorder.core.port.DocumentStore
import com.personal.unifiedrecorder.core.port.StatusLog

/** The decision the capture-begin guard reaches for a given call state (Property 4). */
enum class CaptureBeginDecision {
    /** Begin a brand-new capture: ACTIVE, RECORD_AUDIO granted, and nothing in progress. */
    BEGIN_NEW,

    /** Continue the capture already running: ACTIVE while a capture is already in progress. */
    CONTINUE_EXISTING,

    /** Skip capture and log a missing-permission status: ACTIVE without the RECORD_AUDIO grant. */
    SKIP_PERMISSION_MISSING,

    /** No capture action applies (the state is not ACTIVE). */
    IGNORE
}

/** One (averageAmplitude, durationMillis) point of a capture path's amplitude-over-time series. */
data class AmplitudeSample(val averageAmplitude: Int, val durationMillis: Long)

/**
 * A scripted single capture-path attempt used to drive the ordered fallback:
 * whether the path configured successfully, and the amplitude-over-time series it
 * produced once configured. Time is expressed purely through frame durations so the
 * pipeline never touches a real clock.
 */
data class PathScript(
    val configSucceeds: Boolean,
    val timeline: List<AmplitudeSample> = emptyList()
)

/** The classification of a single capture path once evaluated (Properties 11, 12). */
data class PathEvaluation(
    /** True once the path produced an audible frame (amplitude at/above the threshold). */
    val audibleDetected: Boolean,
    /** True when the path was continuously silent for at least the failover window. */
    val silentFailover: Boolean,
    /** Milliseconds of audio consumed before the path resolved (bounded by the budget). */
    val consumedMillis: Long
)

/** Outcome of running the ordered fallback across candidate paths (Properties 10, 13). */
data class FallbackResult(
    val succeeded: Boolean,
    val attempts: Int,
    val elapsedMillis: Long
)

/** Outcome of finalizing a capture to storage (Properties 5, 23, 24, 28). */
data class FinalizeResult(
    val audioWritten: Boolean,
    val metadataWritten: Boolean,
    /** True when finalize did not complete cleanly (abnormal stop or aborted). */
    val abnormal: Boolean,
    /** True when the write was aborted before any bytes were written (storage unavailable). */
    val abortedBeforeWrite: Boolean
)

/**
 * Drives the deterministic capture pipeline via ports (Requirements 2.x, 3.4–3.8, 6.6–6.11, 9.3).
 *
 * The orchestrator is pure coordination logic: every Android/OS touch point is a port, and time
 * is driven through injected [Clock] timestamps and per-frame durations rather than real
 * wall-clock delays, so all behaviors are exercised deterministically on the JVM.
 *
 * Responsibilities:
 * - **Capture-begin guard & lifecycle** ([decideBegin], [handleActive], [handleCaptureFailure]) —
 *   Requirements 2.1, 2.4, 2.6, 2.7.
 * - **Ordered fallback, silence failover, and path logging** ([evaluatePath], [runFallback]) —
 *   Requirements 3.4, 3.5, 3.6, 3.7.
 * - **Finalize with storage/metadata guards and directory indexing** ([buildAudioProfile],
 *   [finalize], [indexRecordings]) — Requirements 3.8, 4.4, 6.6, 6.7, 6.8, 6.11, 9.3.
 */
class CapturePathOrchestrator(
    private val clock: Clock,
    private val statusLog: StatusLog,
    private val documentStore: DocumentStore
) {

    /** True while a capture is in progress; drives the active-recording indicator (Req 2.5). */
    var captureInProgress: Boolean = false
        private set

    // -----------------------------------------------------------------------
    // 17.1 Capture-begin guard and lifecycle
    // -----------------------------------------------------------------------

    /**
     * Apply the capture-begin guard for an ACTIVE call state and perform the corresponding
     * side effects: mark capture in progress and log [StatusEntry.CaptureStarted] when beginning,
     * or log [StatusEntry.PermissionMissing] when skipping for a missing RECORD_AUDIO grant.
     *
     * @return the [CaptureBeginDecision] that was applied (also returned for [CallState] values
     *   other than ACTIVE, which always yield [CaptureBeginDecision.IGNORE]).
     */
    fun handleActive(recordAudioGranted: Boolean, mode: ExecutionMode): CaptureBeginDecision {
        val decision = decideBegin(CallState.ACTIVE, recordAudioGranted, captureInProgress)
        when (decision) {
            CaptureBeginDecision.BEGIN_NEW -> {
                captureInProgress = true
                statusLog.record(StatusEntry.CaptureStarted(clock.nowMillis(), mode))
            }
            CaptureBeginDecision.CONTINUE_EXISTING -> {
                // Req 2.7: keep the existing capture; do not start a new one.
            }
            CaptureBeginDecision.SKIP_PERMISSION_MISSING -> {
                statusLog.record(
                    StatusEntry.PermissionMissing(clock.nowMillis(), RuntimePermission.RECORD_AUDIO)
                )
            }
            CaptureBeginDecision.IGNORE -> {
                // Not applicable for ACTIVE; retained for completeness.
            }
        }
        return decision
    }

    /**
     * Handle a capture that fails to begin or continue with no usable audio (Req 2.6): stop the
     * attempt, discard the partial (nothing is persisted), and log an abnormal-stop status entry.
     */
    fun handleCaptureFailure() {
        captureInProgress = false
        statusLog.record(StatusEntry.CaptureStopped(clock.nowMillis(), abnormal = true))
    }

    // -----------------------------------------------------------------------
    // 17.5 Ordered fallback, silence failover, and path logging
    // -----------------------------------------------------------------------

    /**
     * Classify one capture path from its configuration outcome and amplitude-over-time series.
     *
     * A configured path is *audible* as soon as a frame reaches [threshold]; it is a *silent
     * failover* when it stays continuously below [threshold] for at least [silenceFailoverMillis]
     * before any audible frame (Req 3.5). A path that failed to configure is neither audible nor a
     * silence failover and consumes no time.
     */
    fun evaluatePath(
        script: PathScript,
        threshold: Int,
        silenceFailoverMillis: Long = SILENCE_FAILOVER_MILLIS
    ): PathEvaluation {
        if (!script.configSucceeds) {
            return PathEvaluation(audibleDetected = false, silentFailover = false, consumedMillis = 0L)
        }
        var silentStreak = 0L
        var consumed = 0L
        for (sample in script.timeline) {
            consumed += sample.durationMillis
            if (sample.averageAmplitude >= threshold) {
                return PathEvaluation(audibleDetected = true, silentFailover = false, consumedMillis = consumed)
            }
            silentStreak += sample.durationMillis
            if (silentStreak >= silenceFailoverMillis) {
                return PathEvaluation(audibleDetected = false, silentFailover = true, consumedMillis = consumed)
            }
        }
        return PathEvaluation(audibleDetected = false, silentFailover = false, consumedMillis = consumed)
    }

    /**
     * Run the ordered fallback across [scripts] (Req 3.4, 3.6, 3.7): make at most
     * [MAX_ATTEMPTS] attempts within the [BUDGET_MILLIS] budget, log exactly one
     * [StatusEntry.PathAttempt] per attempt, log a single [StatusEntry.PathSucceeded] and stop on
     * the first audible path, and otherwise append [StatusEntry.CaptureUnestablished]. The status
     * log is only appended to — prior entries are never removed.
     */
    fun runFallback(scripts: List<PathScript>, threshold: Int): FallbackResult {
        var attempt = 0
        var elapsed = 0L
        for (script in scripts) {
            if (attempt >= MAX_ATTEMPTS || elapsed >= BUDGET_MILLIS) break
            attempt++
            val attemptTs = clock.nowMillis()
            val evaluation = evaluatePath(script, threshold)
            elapsed += evaluation.consumedMillis
            val success = evaluation.audibleDetected
            statusLog.record(StatusEntry.PathAttempt(attemptTs, attempt, success))
            if (success) {
                statusLog.record(StatusEntry.PathSucceeded(clock.nowMillis()))
                return FallbackResult(succeeded = true, attempts = attempt, elapsedMillis = elapsed)
            }
        }
        statusLog.record(StatusEntry.CaptureUnestablished(clock.nowMillis()))
        return FallbackResult(succeeded = false, attempts = attempt, elapsedMillis = elapsed)
    }

    // -----------------------------------------------------------------------
    // 17.10 Finalize: metadata/storage guards and directory indexing
    // -----------------------------------------------------------------------

    /**
     * Build the audio_profile for a completed call (Req 3.8, 4.4): record the selected
     * [mode] and the [skippedSilentSeconds], and include [dspFilterId] if and only if the
     * mode is [ExecutionMode.UNROOTED_LOUDSPEAKER] (DSP applied).
     */
    fun buildAudioProfile(
        mode: ExecutionMode,
        dspFilterId: String,
        skippedSilentSeconds: Int
    ): AudioProfile =
        AudioProfile(
            executionMode = mode,
            dspFilterId = if (mode == ExecutionMode.UNROOTED_LOUDSPEAKER) dspFilterId else null,
            skippedSilentSeconds = skippedSilentSeconds
        )

    /**
     * Finalize a capture to the bound directory.
     *
     * Guards, in order:
     * - **Storage-unavailable abort** (Req 6.7): if the directory is unbound or free space is
     *   insufficient at the moment writing begins, abort before writing, log a
     *   `StorageFailure("storage-unavailable")`, and preserve the in-memory data.
     * - **Mid-write failure** (Req 6.6 / 2.3): if writing the audio fails, log a `StorageFailure`,
     *   keep any written portion, delete no other recording, and log
     *   `CaptureStopped(abnormal=true)` to preserve the captured portion.
     * - **Metadata-write failure** (Req 6.11): if writing the companion fails, log a
     *   `MetadataWriteFailure` and retain the audio recording.
     */
    suspend fun finalize(
        audioBytes: ByteArray,
        fileName: String,
        metadata: MetadataCompanion
    ): FinalizeResult {
        captureInProgress = false

        // Req 6.7: storage-unavailable abort guard, evaluated before any write.
        if (!documentStore.isBound() || documentStore.freeBytes() < audioBytes.size.toLong()) {
            statusLog.record(StatusEntry.StorageFailure(clock.nowMillis(), STORAGE_UNAVAILABLE))
            return FinalizeResult(
                audioWritten = false,
                metadataWritten = false,
                abnormal = true,
                abortedBeforeWrite = true
            )
        }

        // Req 6.6 / 2.3: write audio; on failure retain the written portion and preserve the file.
        val audioWritten = try {
            val sink = documentStore.createDocument(fileName, mimeFor(fileName))
            sink.write(audioBytes, 0, audioBytes.size)
            sink.close()
            true
        } catch (e: Exception) {
            statusLog.record(StatusEntry.StorageFailure(clock.nowMillis(), "write-failed: ${e.message}"))
            false
        }
        if (!audioWritten) {
            statusLog.record(StatusEntry.CaptureStopped(clock.nowMillis(), abnormal = true))
            return FinalizeResult(
                audioWritten = false,
                metadataWritten = false,
                abnormal = true,
                abortedBeforeWrite = false
            )
        }

        // Req 6.11: write the companion; on failure keep the audio recording.
        val metadataWritten = try {
            documentStore.writeText(
                MetadataCodec.companionFileNameFor(fileName),
                MetadataCodec.encode(metadata)
            )
            true
        } catch (e: Exception) {
            statusLog.record(StatusEntry.MetadataWriteFailure(clock.nowMillis()))
            false
        }

        return FinalizeResult(
            audioWritten = true,
            metadataWritten = metadataWritten,
            abnormal = !metadataWritten,
            abortedBeforeWrite = false
        )
    }

    /**
     * Index the bound directory to restore call history (Req 6.8, 9.3): list only documents under
     * the bound tree, and for each audio recording read its adjacent companion to restore metadata
     * and annotations. Listing is confined to the store, so content outside the bound tree can
     * never be surfaced.
     */
    suspend fun indexRecordings(): List<RecordingEntry> {
        val documents = documentStore.listRecordings()
        return documents
            .filter { it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS }
            .map { document ->
                val companionName = MetadataCodec.companionFileNameFor(document.name)
                val metadata = documentStore.readText(companionName)?.let { MetadataCodec.decode(it) }
                RecordingEntry(
                    fileName = document.name,
                    parsed = FilenameCodec.parse(document.name),
                    metadata = metadata
                )
            }
    }

    private fun mimeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "wav" -> "audio/wav"
            "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }

    companion object {
        /** Maximum ordered fallback attempts (Req 3.4). */
        const val MAX_ATTEMPTS: Int = 3

        /** Total budget for establishing capture across all paths, in milliseconds (Req 3.7). */
        const val BUDGET_MILLIS: Long = 6_000L

        /** Continuous-silence duration that fails a path and triggers failover (Req 3.5). */
        const val SILENCE_FAILOVER_MILLIS: Long = 3_000L

        /** Reason recorded when the directory is unavailable or lacks free space (Req 6.7). */
        const val STORAGE_UNAVAILABLE: String = "storage-unavailable"

        private val AUDIO_EXTENSIONS: Set<String> = setOf("wav", "mp4", "aac")

        /**
         * The pure capture-begin guard (Req 2.1, 2.4, 2.7): begin a new capture if and only if the
         * state is ACTIVE, RECORD_AUDIO is granted, and no capture is in progress; continue the
         * existing capture when ACTIVE with one already in progress; skip (permission missing) when
         * ACTIVE without the grant; ignore every non-ACTIVE state.
         */
        fun decideBegin(
            state: CallState,
            recordAudioGranted: Boolean,
            captureInProgress: Boolean
        ): CaptureBeginDecision =
            when {
                state != CallState.ACTIVE -> CaptureBeginDecision.IGNORE
                captureInProgress -> CaptureBeginDecision.CONTINUE_EXISTING
                !recordAudioGranted -> CaptureBeginDecision.SKIP_PERMISSION_MISSING
                else -> CaptureBeginDecision.BEGIN_NEW
            }
    }
}
