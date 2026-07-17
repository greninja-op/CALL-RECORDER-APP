package com.personal.unifiedrecorder.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Lightweight ViewModel holding the onboarding screen's transient UI state that
 * is not owned by the core evaluator: the settings-open error message
 * (Req 9.7). The onboarding preconditions themselves (accessibility enabled,
 * directory bound) are computed by the core `OnboardingStateEvaluator` and fed
 * in by the wiring layer.
 */
class OnboardingViewModel : ViewModel() {

    var settingsErrorMessage by mutableStateOf<String?>(null)
        private set

    /** Record that a system settings screen could not be opened (Req 9.7). */
    fun reportSettingsOpenFailed(message: String) {
        settingsErrorMessage = message
    }

    /** Clear the settings-open error once the user retries or navigates. */
    fun clearSettingsError() {
        settingsErrorMessage = null
    }
}
