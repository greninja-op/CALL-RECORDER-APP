package com.personal.unifiedrecorder.monitoring

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.personal.unifiedrecorder.adapter.RecordingIndicatorService
import com.personal.unifiedrecorder.core.data.MetadataCodec
import com.personal.unifiedrecorder.core.logic.CallStateMachine
import com.personal.unifiedrecorder.core.logic.FilenameCodec
import com.personal.unifiedrecorder.core.model.AudioFormat
import com.personal.unifiedrecorder.core.model.CallState
import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.CaptureConfig
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.ExecutionMode
import com.personal.unifiedrecorder.core.model.IdentityContext
import com.personal.unifiedrecorder.core.model.MetadataCompanion
import com.personal.unifiedrecorder.core.model.RuntimePermission
import com.personal.unifiedrecorder.core.onboarding.OnboardingStateEvaluator
import com.personal.unifiedrecorder.core.orchestrator.CaptureBeginDecision
import com.personal.unifiedrecorder.core.policy.AudioFormatPolicy
import com.personal.unifiedrecorder.core.policy.ExecutionModeSelector
import com.personal.unifiedrecorder.core.port.AudioCaptureDevice
import com.personal.unifiedrecorder.core.port.ByteSink
import com.personal.unifiedrecorder.di.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId

/**
 * Connects the accessibility event flow to the call-state machine and the capture orchestrator so
 * captures begin and end with calls, and drives the active-recording foreground indicator
 * (Requirements 2.5, 9.1, 9.8, 10.2, 10.5, 10.8).
 *
 * A capture is begun only when every gate passes at the moment a call becomes ACTIVE:
 * - onboarding is complete (accessibility enabled AND a Target_Directory bound) — [OnboardingStateEvaluator],
 * - the user has acknowledged the Consent_Notice — [com.personal.unifiedrecorder.core.consent.ConsentGate],
 * - the RECORD_AUDIO permission is granted — evaluated through the [CapturePathOrchestrator] begin guard.
 *
 * The pure [CapturePathOrchestrator] owns the begin guard, status logging, capture-in-progress flag,
 * and finalize/storage guards. This controller supplies the Android side effects (real capture via an
 * [AudioCaptureDevice], loudspeaker routing, and the indicator service), buffering the captured bytes
 * and handing them to [CapturePathOrchestrator.finalize] on call end.
 *
 * Device-only / manual verification: real accessibility events, microphone capture, routing, and the
 * foreground indicator can only be exercised on a device.
 */
class CallMonitoringController(
    private val graph: AppGraph
) {
    private val appContext: Context = graph.appContext
    private val scope get() = graph.appScope

    // Serializes the ACTIVE/ENDED handlers so overlapping events cannot interleave capture setup.
    private val mutex = Mutex()

    @Volatile
    private var started = false

    private var snapshot = CallStateMachine.initial(CallState.ENDED)

    // In-flight capture bookkeeping.
    private var captureJob: Job? = null
    private var currentSink: BufferingByteSink? = null
    private var currentDevice: AudioCaptureDevice? = null
    private var currentMode: ExecutionMode = ExecutionMode.UNROOTED_LOUDSPEAKER
    private var currentFormat: AudioFormat = AudioFormat.WAV
    private var currentFileName: String? = null
    private var currentCallType: CallType = CallType.CELLULAR
    private var callStartMillis: Long = 0L

    /** Begin observing accessibility events. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            graph.accessibilityEventSource.events().collect { event ->
                val previous = snapshot.state
                snapshot = CallStateMachine.reduce(snapshot, event)
                val next = snapshot.state
                if (previous == next) return@collect
                when (next) {
                    CallState.ACTIVE -> onCallActive(callTypeFor(event.packageName))
                    CallState.ENDED -> onCallEnded()
                    else -> Unit
                }
            }
        }
    }

    private suspend fun onCallActive(callType: CallType) = mutex.withLock {
        // Gate 1 & 2: onboarding complete and consent acknowledged (Req 9.1, 9.8, 10.2, 10.5).
        val directoryBound = runCatching { graph.documentStore.isBound() }.getOrDefault(false)
        val accessibilityEnabled = graph.accessibilityEventSource.enabled
        val onboarding = OnboardingStateEvaluator.evaluate(accessibilityEnabled, directoryBound)
        if (!onboarding.monitoringAllowed) return
        if (!graph.consentGate.recordingPermitted()) return

        // Gate 3 (RECORD_AUDIO) is enforced inside the orchestrator begin guard.
        val recordAudioGranted = graph.permissionChecker.status(RuntimePermission.RECORD_AUDIO)
        val superuserAvailable = runCatching { graph.superuserProbe.isSuperuserAvailable() }
            .getOrDefault(false)
        val mode = ExecutionModeSelector.select(superuserAvailable)

        val decision = graph.orchestrator.handleActive(recordAudioGranted, mode)
        if (decision != CaptureBeginDecision.BEGIN_NEW) return

        beginCapture(mode, callType)
        // The indicator is tied to the orchestrator's capture-in-progress flag (Req 2.5).
        if (graph.orchestrator.captureInProgress) startIndicator()
    }

    private suspend fun beginCapture(mode: ExecutionMode, callType: CallType) {
        // Unrooted loudspeaker capture applies DSP + Smart Silence, forcing the WAV/PCM path (Req 6.3).
        val dspActive = mode == ExecutionMode.UNROOTED_LOUDSPEAKER
        val format = AudioFormatPolicy.select(
            dspActive = dspActive,
            smartSilenceActive = dspActive,
            configuredFormat = null
        )
        val device: AudioCaptureDevice =
            if (format == AudioFormat.WAV) graph.audioRecordCaptureDevice
            else graph.mediaRecorderCaptureDevice

        val existing = runCatching { graph.documentStore.existingNames() }.getOrDefault(emptySet())
        val start = graph.clock.nowMillis()
        val local = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
        // Direction cannot be resolved from a window event; default to INCOMING (Req 6.10 covers
        // unresolved identity fields; the filename grammar has no UNKNOWN direction token).
        val direction = Direction.INCOMING
        val fileName = FilenameCodec.encode(
            callType = callType,
            direction = direction,
            year = local.year,
            month = local.monthValue,
            day = local.dayOfMonth,
            hour = local.hour,
            minute = local.minute,
            second = local.second,
            format = format,
            existingNames = existing
        )

        val sink = BufferingByteSink()
        currentSink = sink
        currentDevice = device
        currentMode = mode
        currentFormat = format
        currentFileName = fileName
        currentCallType = callType
        callStartMillis = start

        val config = CaptureConfig(executionMode = mode, format = format)
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                if (mode == ExecutionMode.UNROOTED_LOUDSPEAKER) {
                    runCatching { graph.audioRoutingController.routeToLoudspeaker() }
                }
                // Streams captured bytes into the buffer until stop() is invoked on call end.
                device.captureEncoded(config, sink)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // No usable audio was captured (Req 2.6): discard the partial and log the failure.
                graph.orchestrator.handleCaptureFailure()
            }
        }
    }

    private suspend fun onCallEnded() = mutex.withLock {
        val device = currentDevice ?: return
        val sink = currentSink
        val fileName = currentFileName ?: return

        // Stop the capture loop and let the streaming job finish draining into the buffer.
        runCatching { device.stop() }
        runCatching { captureJob?.join() }
        if (currentMode == ExecutionMode.UNROOTED_LOUDSPEAKER) {
            runCatching { graph.audioRoutingController.restorePreviousRouting() }
        }

        val audioBytes = sink?.toByteArray() ?: ByteArray(0)
        val end = graph.clock.nowMillis()
        val durationSeconds = ((end - callStartMillis).coerceAtLeast(0L)) / 1000L
        val metadata = MetadataCompanion(
            callType = currentCallType,
            direction = Direction.INCOMING,
            identity = IdentityContext(),
            timestampStartMillis = callStartMillis,
            durationSeconds = durationSeconds,
            fileExtension = FilenameCodec.extensionOf(currentFormat),
            audioProfile = graph.orchestrator.buildAudioProfile(
                mode = currentMode,
                dspFilterId = DSP_FILTER_ID,
                skippedSilentSeconds = 0
            )
        )

        // Finalize applies the storage-unavailable / mid-write / metadata guards and resets the
        // capture-in-progress flag (Req 6.6, 6.7, 6.11, 2.3).
        runCatching { graph.orchestrator.finalize(audioBytes, fileName, metadata) }

        clearCaptureState()
        // Indicator follows the orchestrator's capture-in-progress flag (Req 2.5).
        if (!graph.orchestrator.captureInProgress) stopIndicator()
    }

    private fun clearCaptureState() {
        captureJob = null
        currentSink = null
        currentDevice = null
        currentFileName = null
    }

    private fun startIndicator() {
        val intent = Intent(appContext, RecordingIndicatorService::class.java).apply {
            action = RecordingIndicatorService.ACTION_START
        }
        ContextCompat.startForegroundService(appContext, intent)
    }

    private fun stopIndicator() {
        val intent = Intent(appContext, RecordingIndicatorService::class.java).apply {
            action = RecordingIndicatorService.ACTION_STOP
        }
        // A plain start delivers ACTION_STOP; the service then stops itself.
        runCatching { appContext.startService(intent) }
    }

    private fun callTypeFor(packageName: String): CallType =
        if (packageName == CallStateMachine.TARGET_VOIP_PACKAGE) CallType.WHATSAPP else CallType.CELLULAR

    /**
     * In-memory [ByteSink] that accumulates captured bytes so they can be handed to
     * [CapturePathOrchestrator.finalize] once the call ends.
     */
    private class BufferingByteSink : ByteSink {
        private val lock = Any()
        private val buffer = ByteArrayOutputStream()

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            synchronized(lock) { buffer.write(bytes, offset, length) }
        }

        override suspend fun close() { /* nothing to flush for an in-memory buffer */ }

        override suspend fun abort() {
            synchronized(lock) { buffer.reset() }
        }

        fun toByteArray(): ByteArray = synchronized(lock) { buffer.toByteArray() }
    }

    private companion object {
        const val DSP_FILTER_ID = "agc_lowpass_v1"
    }
}
