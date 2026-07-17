package com.personal.unifiedrecorder.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.personal.unifiedrecorder.ui.consent.ConsentDetailsDialog
import com.personal.unifiedrecorder.ui.consent.ConsentScreen
import com.personal.unifiedrecorder.ui.dashboard.DashboardScreen
import com.personal.unifiedrecorder.ui.onboarding.OnboardingScreen
import com.personal.unifiedrecorder.ui.permission.PermissionScreen
import com.personal.unifiedrecorder.ui.theme.UnifiedRecorderTheme

/**
 * Top-level composable that selects and renders the active screen from
 * [state]'s [AppUiState.destination] and forwards user intents through
 * [callbacks]. The final application wiring (a later task) hosts this from
 * `MainActivity`, supplying the state derived from the core state holders.
 *
 * This composable does not own navigation state itself — the destination is a
 * pure function of the application/core state, keeping the selection testable
 * and the wiring flexible.
 */
@Composable
fun UnifiedRecorderApp(
    state: AppUiState,
    callbacks: AppCallbacks,
    modifier: Modifier = Modifier
) {
    UnifiedRecorderTheme {
        when (state.destination) {
            AppDestination.CONSENT ->
                ConsentScreen(callbacks = callbacks.consent, modifier = modifier)

            AppDestination.PERMISSIONS ->
                PermissionScreen(
                    state = state.permission,
                    callbacks = callbacks.permission,
                    modifier = modifier
                )

            AppDestination.ONBOARDING ->
                OnboardingScreen(
                    state = state.onboarding,
                    callbacks = callbacks.onboarding,
                    modifier = modifier
                )

            AppDestination.DASHBOARD -> {
                DashboardScreen(
                    state = state.dashboard,
                    callbacks = callbacks.dashboard,
                    modifier = modifier
                )
                // Full Consent_Notice content surfaced from the dashboard control
                // after acknowledgment (Req 10.7).
                if (state.consent.showFullNoticeDialog) {
                    ConsentDetailsDialog(onDismiss = callbacks.consent.onDismissFullNotice)
                }
            }
        }
    }
}

/**
 * Alias for [UnifiedRecorderApp] matching the "nav host" naming used in the
 * task description; provided so the wiring task can host either name.
 */
@Composable
fun AppNavHost(
    state: AppUiState,
    callbacks: AppCallbacks,
    modifier: Modifier = Modifier
) = UnifiedRecorderApp(state = state, callbacks = callbacks, modifier = modifier)
