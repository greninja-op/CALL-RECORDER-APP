package com.personal.unifiedrecorder.core.onboarding

/**
 * Which top-level screen the Onboarding_Guide must surface for a given application state.
 *
 * [CONFIGURATION] is the mandatory setup screen shown until onboarding completes; [DASHBOARD]
 * is only reachable once setup is complete.
 */
enum class OnboardingScreen { CONFIGURATION, DASHBOARD }

/**
 * Immutable view-state describing whether onboarding is complete and what the UI/monitoring
 * layers are permitted to do.
 *
 * @property requiresConfiguration true while the mandatory configuration screen must be shown.
 * @property dashboardUnlocked true when the main Dashboard may be presented.
 * @property monitoringAllowed true when background call monitoring may start.
 * @property setupComplete true when both onboarding conditions are satisfied.
 * @property activeScreen the screen the Onboarding_Guide should display.
 */
data class OnboardingState(
    val requiresConfiguration: Boolean,
    val dashboardUnlocked: Boolean,
    val monitoringAllowed: Boolean,
    val setupComplete: Boolean,
    val activeScreen: OnboardingScreen
)

/**
 * Pure evaluator that maps the two onboarding preconditions to an [OnboardingState].
 *
 * Requirements:
 * - 9.1: With no valid Target_Directory bound, the mandatory configuration screen is required,
 *   the Dashboard stays locked, and background monitoring must not start.
 * - 9.8: Setup is reported complete and the Dashboard is unlocked if and only if the
 *   Accessibility_Engine is enabled AND a valid Target_Directory is bound.
 *
 * No `android.*` dependencies — this is a plain deterministic function usable on the JVM.
 */
object OnboardingStateEvaluator {

    /**
     * @param accessibilityEnabled whether the Accessibility_Engine is detected as enabled.
     * @param directoryBound whether a valid Target_Directory tree URI is securely bound.
     */
    fun evaluate(accessibilityEnabled: Boolean, directoryBound: Boolean): OnboardingState {
        // Req 9.8: onboarding completes exactly when both conditions hold.
        val setupComplete = accessibilityEnabled && directoryBound

        // Req 9.1: until a directory is bound the configuration screen is mandatory, the
        // Dashboard is locked, and monitoring may not start. This falls out of the fact that
        // setupComplete can only be true when directoryBound is true.
        return OnboardingState(
            requiresConfiguration = !setupComplete,
            dashboardUnlocked = setupComplete,
            monitoringAllowed = setupComplete,
            setupComplete = setupComplete,
            activeScreen = if (setupComplete) OnboardingScreen.DASHBOARD else OnboardingScreen.CONFIGURATION
        )
    }
}
