package com.personal.unifiedrecorder.di

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.personal.unifiedrecorder.adapter.AndroidPermissionChecker
import com.personal.unifiedrecorder.adapter.AudioManagerRoutingController
import com.personal.unifiedrecorder.adapter.AudioRecordCaptureDevice
import com.personal.unifiedrecorder.adapter.MediaPlayerController
import com.personal.unifiedrecorder.adapter.MediaRecorderCaptureDevice
import com.personal.unifiedrecorder.adapter.ObservableStatusLog
import com.personal.unifiedrecorder.adapter.SafDocumentStore
import com.personal.unifiedrecorder.adapter.SharedPreferencesAcknowledgmentStore
import com.personal.unifiedrecorder.adapter.ShellSuperuserProbe
import com.personal.unifiedrecorder.adapter.SystemClock
import com.personal.unifiedrecorder.adapter.UnifiedAccessibilityEventSource
import com.personal.unifiedrecorder.core.consent.ConsentGate
import com.personal.unifiedrecorder.core.orchestrator.CapturePathOrchestrator
import com.personal.unifiedrecorder.core.port.AccessibilityEventSource
import com.personal.unifiedrecorder.core.port.AudioRoutingController
import com.personal.unifiedrecorder.core.port.Clock
import com.personal.unifiedrecorder.core.port.DocumentStore
import com.personal.unifiedrecorder.core.port.MediaPlaybackController
import com.personal.unifiedrecorder.core.port.PermissionChecker
import com.personal.unifiedrecorder.core.port.SuperuserProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency-injection graph for the application (Task 23.1).
 *
 * Constructed once from the [android.app.Application] context, it instantiates every Android
 * adapter and binds it to the pure-core components, holding them as process-wide singletons. The
 * core stays `android.*`-free: only the adapters constructed here touch the platform, and the core
 * components ([CapturePathOrchestrator], [ConsentGate], selectors, evaluators) depend solely on the
 * ports these adapters implement.
 *
 * No framework component (Activity/Service) constructs its own collaborators; they read the shared
 * instances from here so state (bound directory, consent flag, capture progress, status log) is
 * consistent across the process.
 */
class AppGraph(context: Context) {

    /** Application context; never an Activity, so it is safe to retain for the process lifetime. */
    val appContext: Context = context.applicationContext

    /**
     * The Activity currently in the foreground, set by `MainActivity` while resumed. Used by
     * [AndroidPermissionChecker] to evaluate the "don't ask again" permanently-denied heuristic,
     * which requires an Activity. Cleared when no Activity is resumed.
     */
    @Volatile
    var currentActivity: Activity? = null

    /** Long-lived scope for background monitoring; survives Activity recreation. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Cross-cutting ports -------------------------------------------------

    val clock: Clock = SystemClock()

    val statusLog: ObservableStatusLog = ObservableStatusLog()

    val permissionChecker: PermissionChecker =
        AndroidPermissionChecker(appContext, activityProvider = { currentActivity })

    // --- Storage / consent singletons ---------------------------------------

    val documentStore: DocumentStore = SafDocumentStore(appContext)

    private val acknowledgmentStore = SharedPreferencesAcknowledgmentStore(appContext)

    val consentGate: ConsentGate = ConsentGate(acknowledgmentStore)

    // --- Accessibility / capture / routing / playback ------------------------

    val accessibilityEventSource: AccessibilityEventSource =
        UnifiedAccessibilityEventSource(appContext)

    val superuserProbe: SuperuserProbe = ShellSuperuserProbe()

    val audioRoutingController: AudioRoutingController = AudioManagerRoutingController(appContext)

    /** PCM path used whenever DSP / Smart Silence is active (WAV output). */
    val audioRecordCaptureDevice = AudioRecordCaptureDevice()

    /** Encoded path (AAC/MP4) for the plain unrooted and rooted stealth paths. */
    val mediaRecorderCaptureDevice = MediaRecorderCaptureDevice()
        .also { it.context = appContext }

    val mediaPlaybackController: MediaPlaybackController =
        MediaPlayerController(appContext, treeUriProvider = { boundTreeUri() })

    // --- Core orchestration --------------------------------------------------

    val orchestrator: CapturePathOrchestrator =
        CapturePathOrchestrator(clock = clock, statusLog = statusLog, documentStore = documentStore)

    // --- Bound-directory helpers ---------------------------------------------

    /**
     * The persisted Target_Directory tree URI, or null when none is bound. Read from the same
     * SharedPreferences [SafDocumentStore] persists to, so playback and labelling stay in sync
     * without widening the adapter's surface.
     */
    fun boundTreeUri(): String? =
        appContext.getSharedPreferences(SAF_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SAF_KEY_TREE_URI, null)

    /** A short, human-readable label for the bound directory, or null when none is bound. */
    fun boundDirectoryLabel(): String? {
        val uri = boundTreeUri() ?: return null
        val lastSegment = runCatching { Uri.parse(uri).lastPathSegment }.getOrNull() ?: return null
        return lastSegment.substringAfterLast(':').substringAfterLast('/').ifEmpty { lastSegment }
    }

    private companion object {
        // Mirror of SafDocumentStore's private persistence keys (kept in sync intentionally).
        const val SAF_PREFS_NAME = "saf_document_store"
        const val SAF_KEY_TREE_URI = "tree_uri"
    }
}
