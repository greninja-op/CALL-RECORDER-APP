package com.personal.unifiedrecorder.ui

import androidx.compose.runtime.Immutable
import com.personal.unifiedrecorder.core.model.CallTypeFilter
import com.personal.unifiedrecorder.core.model.DashboardFilterState
import com.personal.unifiedrecorder.core.model.DirectionFilter
import com.personal.unifiedrecorder.core.model.RecordingEntry
import com.personal.unifiedrecorder.core.onboarding.OnboardingState
import com.personal.unifiedrecorder.core.playback.PlaybackState
import com.personal.unifiedrecorder.core.policy.PermissionStateMapper

/**
 * The four top-level destinations the application can present. The final wiring
 * task selects the destination from the core onboarding / consent / permission
 * state and hands it to [UnifiedRecorderApp].
 */
enum class AppDestination { CONSENT, PERMISSIONS, ONBOARDING, DASHBOARD }

/**
 * Immutable view-state for the Consent screen (Req 10). [showFullNoticeDialog]
 * drives the post-acknowledgment full-content view surfaced from the dashboard
 * (Req 10.7).
 */
@Immutable
data class ConsentUiState(
    val showFullNoticeDialog: Boolean = false
)

/**
 * Immutable view-state for the Onboarding screen (Req 9), driven by the core
 * [OnboardingState].
 */
@Immutable
data class OnboardingUiState(
    val onboarding: OnboardingState,
    val accessibilityEnabled: Boolean,
    val directoryBound: Boolean,
    /** Human-readable label for the bound directory, or null when none is bound. */
    val boundDirectoryLabel: String? = null,
    /** True on Android 13+ where Restricted Settings guidance is relevant (Req 9.5). */
    val showRestrictedSettingsHelp: Boolean = false,
    /** Non-null when a settings screen could not be opened (Req 9.7). */
    val settingsErrorMessage: String? = null
)

/**
 * Immutable view-state for the Permission screen (Req 8), driven by the core
 * [PermissionStateMapper.PermissionState].
 */
@Immutable
data class PermissionUiState(
    val permissionState: PermissionStateMapper.PermissionState
)

/**
 * Immutable view-state for the Dashboard screen (Req 5 / 7). Filtering, sorting,
 * and empty-state detection are delegated to the core `DashboardFilter`; the
 * screen receives the raw entries and the active filter selections.
 */
@Immutable
data class DashboardUiState(
    val allEntries: List<RecordingEntry>,
    val filterState: DashboardFilterState = DashboardFilterState(),
    val playback: PlaybackState,
    /** File name of the recording whose bookmark overlay is open, or null. */
    val bookmarkTarget: String? = null
)

/**
 * Aggregate application view-state selecting the active [destination] and
 * carrying every sub-screen's state. The wiring task constructs this from the
 * core state holders.
 */
@Immutable
data class AppUiState(
    val destination: AppDestination,
    val consent: ConsentUiState,
    val onboarding: OnboardingUiState,
    val permission: PermissionUiState,
    val dashboard: DashboardUiState
)

/** Callbacks the Consent screen forwards user intents through (Req 10). */
class ConsentCallbacks(
    val onAcknowledge: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onShowFullNotice: () -> Unit = {},
    val onDismissFullNotice: () -> Unit = {}
)

/** Callbacks the Onboarding screen forwards user intents through (Req 9). */
class OnboardingCallbacks(
    /** Reports the tree URI chosen from the SAF directory picker (Req 9.2, 9.3). */
    val onDirectoryChosen: (String) -> Unit = {},
    val onOpenAccessibilitySettings: () -> Unit = {},
    val onOpenAppInfoSettings: () -> Unit = {},
    val onContinue: () -> Unit = {}
)

/** Callbacks the Permission screen forwards user intents through (Req 8). */
class PermissionCallbacks(
    val onRequestPermissions: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onContinue: () -> Unit = {}
)

/** Callbacks the Dashboard screen forwards user intents through (Req 5 / 7). */
class DashboardCallbacks(
    val onCallTypeFilterChange: (CallTypeFilter) -> Unit = {},
    val onDirectionFilterChange: (DirectionFilter) -> Unit = {},
    val onSearchChange: (String) -> Unit = {},
    val onPlay: (String) -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onOpenBookmark: (String) -> Unit = {},
    val onDismissBookmark: () -> Unit = {},
    /** Save a timestamped bookmark for [fileName] at [positionMillis] (Req 7.8). */
    val onSaveBookmark: (fileName: String, positionMillis: Long, text: String) -> Unit =
        { _, _, _ -> },
    val onShowConsent: () -> Unit = {}
)

/** Bundle of every screen's callbacks handed to [UnifiedRecorderApp]. */
class AppCallbacks(
    val consent: ConsentCallbacks = ConsentCallbacks(),
    val onboarding: OnboardingCallbacks = OnboardingCallbacks(),
    val permission: PermissionCallbacks = PermissionCallbacks(),
    val dashboard: DashboardCallbacks = DashboardCallbacks()
)
