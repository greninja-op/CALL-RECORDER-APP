package com.personal.unifiedrecorder

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.unifiedrecorder.adapter.RuntimePermissions
import com.personal.unifiedrecorder.core.data.MetadataCodec
import com.personal.unifiedrecorder.core.logic.FilenameCodec
import com.personal.unifiedrecorder.core.model.AudioFormat
import com.personal.unifiedrecorder.core.model.Bookmark
import com.personal.unifiedrecorder.core.model.CallType
import com.personal.unifiedrecorder.core.model.Direction
import com.personal.unifiedrecorder.core.model.ExecutionMode
import com.personal.unifiedrecorder.core.model.IdentityContext
import com.personal.unifiedrecorder.core.model.MetadataCompanion
import com.personal.unifiedrecorder.core.model.ParsedFilename
import com.personal.unifiedrecorder.core.model.RecordingEntry
import com.personal.unifiedrecorder.core.model.RuntimePermission
import com.personal.unifiedrecorder.core.onboarding.OnboardingStateEvaluator
import com.personal.unifiedrecorder.core.policy.PermissionStateMapper
import com.personal.unifiedrecorder.core.playback.PlaybackOp
import com.personal.unifiedrecorder.di.AppGraph
import com.personal.unifiedrecorder.ui.AppCallbacks
import com.personal.unifiedrecorder.ui.AppDestination
import com.personal.unifiedrecorder.ui.AppUiState
import com.personal.unifiedrecorder.ui.ConsentCallbacks
import com.personal.unifiedrecorder.ui.ConsentUiState
import com.personal.unifiedrecorder.ui.DashboardCallbacks
import com.personal.unifiedrecorder.ui.DashboardUiState
import com.personal.unifiedrecorder.ui.OnboardingCallbacks
import com.personal.unifiedrecorder.ui.OnboardingUiState
import com.personal.unifiedrecorder.ui.PermissionCallbacks
import com.personal.unifiedrecorder.ui.PermissionUiState
import com.personal.unifiedrecorder.ui.UnifiedRecorderApp
import com.personal.unifiedrecorder.ui.dashboard.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Hosts [UnifiedRecorderApp], building [AppUiState] from the core state holders and wiring
 * [AppCallbacks] back to the adapters and core components in the [AppGraph] (Task 23.1).
 *
 * The active destination is a pure function of the core consent / permission / onboarding state.
 * State is recomputed whenever the Activity resumes ([resumeRefresh]) — covering the Restricted
 * Settings return path (Req 7.2 / 9.5) — and after data-changing actions ([dataRefresh]) and
 * permission-result callbacks ([permissionRefresh]).
 */
class MainActivity : ComponentActivity() {

    private val graph: AppGraph
        get() = (application as UnifiedRecorderApplication).graph

    // Recomposition triggers. Incrementing re-runs the produceState/remember blocks below.
    private val resumeRefresh = mutableIntStateOf(0)
    private val dataRefresh = mutableIntStateOf(0)
    private val permissionRefresh = mutableIntStateOf(0)

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Registered before the Activity is STARTED, as required by the Activity Result API.
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            // Re-evaluate permission state once the system dialog returns.
            permissionRefresh.intValue++
        }

        setContent { AppContent() }
    }

    override fun onResume() {
        super.onResume()
        // Track the resumed Activity for the permanently-denied heuristic and recompute onboarding /
        // consent / permission state (Req 7.2 restricted-settings onResume behavior).
        graph.currentActivity = this
        resumeRefresh.intValue++
    }

    override fun onDestroy() {
        if (graph.currentActivity === this) graph.currentActivity = null
        super.onDestroy()
    }

    @Composable
    private fun AppContent() {
        val scope = rememberCoroutineScope()
        val dashboardVm: DashboardViewModel = viewModel()

        val resume = resumeRefresh.intValue
        val data = dataRefresh.intValue
        val permissionTick = permissionRefresh.intValue

        // --- Consent (Req 10) -------------------------------------------------
        var consentAcknowledged by remember { mutableStateOf(graph.consentGate.consentGiven()) }
        var showFullNotice by remember { mutableStateOf(false) }

        // --- Onboarding inputs, recomputed on resume / data change ------------
        val accessibilityEnabled by produceState(
            initialValue = graph.accessibilityEventSource.enabled,
            resume, data
        ) { value = graph.accessibilityEventSource.enabled }

        val directoryBound by produceState(initialValue = false, resume, data) {
            value = withContext(Dispatchers.IO) { graph.documentStore.isBound() }
        }

        val recordings by produceState(
            initialValue = emptyList<RecordingEntry>(),
            resume, data, directoryBound
        ) {
            value = if (directoryBound) {
                withContext(Dispatchers.IO) { runCatching { graph.orchestrator.indexRecordings() }.getOrDefault(emptyList()) }
            } else {
                emptyList()
            }
        }

        var settingsError by remember { mutableStateOf<String?>(null) }

        // --- Permission state (Req 8) ----------------------------------------
        val apiLevel = Build.VERSION.SDK_INT
        val permissionState = remember(resume, permissionTick) {
            val required = PermissionStateMapper.requiredSet(apiLevel)
            val granted = required.filter { graph.permissionChecker.status(it) }.toSet()
            val permanentlyDenied = required.filter { graph.permissionChecker.shouldOpenSettings(it) }.toSet()
            PermissionStateMapper.map(apiLevel, granted, permanentlyDenied)
        }

        val onboarding = OnboardingStateEvaluator.evaluate(accessibilityEnabled, directoryBound)

        val destination = when {
            !consentAcknowledged -> AppDestination.CONSENT
            permissionState.requestSet.isNotEmpty() -> AppDestination.PERMISSIONS
            !onboarding.setupComplete -> AppDestination.ONBOARDING
            else -> AppDestination.DASHBOARD
        }

        val state = AppUiState(
            destination = destination,
            consent = ConsentUiState(showFullNoticeDialog = showFullNotice),
            onboarding = OnboardingUiState(
                onboarding = onboarding,
                accessibilityEnabled = accessibilityEnabled,
                directoryBound = directoryBound,
                boundDirectoryLabel = graph.boundDirectoryLabel(),
                // Restricted Settings guidance is relevant on Android 13+ (Req 9.5).
                showRestrictedSettingsHelp = apiLevel >= Build.VERSION_CODES.TIRAMISU,
                settingsErrorMessage = settingsError
            ),
            permission = PermissionUiState(permissionState = permissionState),
            dashboard = DashboardUiState(
                allEntries = recordings,
                filterState = dashboardVm.filterState,
                playback = dashboardVm.playback,
                bookmarkTarget = dashboardVm.bookmarkTarget
            )
        )

        val callbacks = AppCallbacks(
            consent = ConsentCallbacks(
                onAcknowledge = {
                    graph.consentGate.acknowledge()
                    consentAcknowledged = true
                },
                onDismiss = {
                    graph.consentGate.dismiss()
                    finish()
                },
                onShowFullNotice = { showFullNotice = true },
                onDismissFullNotice = { showFullNotice = false }
            ),
            onboarding = OnboardingCallbacks(
                onDirectoryChosen = { treeUri ->
                    scope.launch {
                        val bound = withContext(Dispatchers.IO) { graph.documentStore.bind(treeUri) }
                        if (!bound) {
                            settingsError = getString(R.string.onboarding_directory_not_bound)
                        } else {
                            settingsError = null
                        }
                        dataRefresh.intValue++
                    }
                },
                onOpenAccessibilitySettings = {
                    openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) { settingsError = it }
                },
                onOpenAppInfoSettings = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                    openSettings(intent) { settingsError = it }
                },
                onContinue = {
                    settingsError = null
                    resumeRefresh.intValue++
                }
            ),
            permission = PermissionCallbacks(
                onRequestPermissions = {
                    val toRequest = permissionState.requestSet
                        .map { RuntimePermissions.androidPermission(it) }
                        .toTypedArray()
                    if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest)
                },
                onOpenSettings = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                    openSettings(intent) { settingsError = it }
                },
                onContinue = { permissionRefresh.intValue++ }
            ),
            dashboard = DashboardCallbacks(
                onCallTypeFilterChange = dashboardVm::setCallTypeFilter,
                onDirectionFilterChange = dashboardVm::setDirectionFilter,
                onSearchChange = dashboardVm::setSearchText,
                onPlay = { name ->
                    scope.launch {
                        val result = graph.mediaPlaybackController.play(name)
                        dashboardVm.applyPlayback(PlaybackOp.Play(name, result))
                    }
                },
                onPause = {
                    graph.mediaPlaybackController.pause()
                    dashboardVm.applyPlayback(PlaybackOp.Pause)
                },
                onResume = {
                    graph.mediaPlaybackController.resume()
                    dashboardVm.applyPlayback(PlaybackOp.Resume)
                },
                onStop = {
                    graph.mediaPlaybackController.stop()
                    dashboardVm.applyPlayback(PlaybackOp.Stop)
                },
                onOpenBookmark = dashboardVm::openBookmark,
                onDismissBookmark = dashboardVm::dismissBookmark,
                onSaveBookmark = { fileName, positionMillis, text ->
                    scope.launch {
                        withContext(Dispatchers.IO) { saveBookmark(fileName, positionMillis, text) }
                        dashboardVm.dismissBookmark()
                        dataRefresh.intValue++
                    }
                },
                onShowConsent = { showFullNotice = true }
            )
        )

        UnifiedRecorderApp(state = state, callbacks = callbacks)
    }

    /** Launch a settings [intent]; on failure surface the error message for the UI (Req 9.7). */
    private fun openSettings(intent: Intent, onError: (String) -> Unit) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            onError(getString(R.string.onboarding_settings_error))
        }
    }

    /**
     * Persist a timestamped bookmark to the recording's companion JSON via [MetadataCodec] and the
     * [com.personal.unifiedrecorder.core.port.DocumentStore] (Req 7.8). Runs on [Dispatchers.IO].
     */
    private suspend fun saveBookmark(fileName: String, positionMillis: Long, text: String) {
        val store = graph.documentStore
        val companionName = MetadataCodec.companionFileNameFor(fileName)
        val existing = store.readText(companionName)?.let { MetadataCodec.decode(it) }
        val base = existing ?: metadataFromFilename(fileName)
        val updated = base.copy(
            userAnnotations = base.userAnnotations.copy(
                bookmarks = base.userAnnotations.bookmarks + Bookmark(positionMillis, text)
            )
        )
        runCatching { store.writeText(companionName, MetadataCodec.encode(updated)) }
    }

    /** Build a best-effort companion from a filename when no companion exists yet. */
    private fun metadataFromFilename(fileName: String): MetadataCompanion {
        val parsed: ParsedFilename? = FilenameCodec.parse(fileName)
        val timestamp = parsed?.let {
            LocalDateTime.of(it.year, it.month, it.day, it.hour, it.minute, it.second)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        } ?: 0L
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val format = when (extension) {
            "wav" -> AudioFormat.WAV
            "mp4" -> AudioFormat.MP4
            else -> AudioFormat.AAC
        }
        return MetadataCompanion(
            callType = parsed?.callType ?: CallType.CELLULAR,
            direction = parsed?.direction ?: Direction.INCOMING,
            identity = IdentityContext(),
            timestampStartMillis = timestamp,
            durationSeconds = 0L,
            fileExtension = FilenameCodec.extensionOf(format),
            audioProfile = graph.orchestrator.buildAudioProfile(
                mode = ExecutionMode.UNROOTED_LOUDSPEAKER,
                dspFilterId = "agc_lowpass_v1",
                skippedSilentSeconds = 0
            )
        )
    }
}
