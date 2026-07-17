package com.personal.unifiedrecorder.core.logic

/**
 * The user-visible call-detection status derived purely from whether the
 * Accessibility_Engine is enabled (Requirements 1.6, 1.7).
 *
 * The concrete presentation (icon, colour, localized copy) lives in the Android
 * UI layer; this pure mapping keeps the enabled → active / disabled → inactive
 * decision deterministic and JVM-testable.
 */
enum class CallDetectionStatus {
    /** The Accessibility_Engine is enabled and call detection is running (Req 1.7). */
    ACTIVE,

    /** The Accessibility_Engine is disabled and call detection cannot run (Req 1.6). */
    INACTIVE
}

/** Maps Accessibility_Engine enablement to the displayed call-detection status. */
object AccessibilityStatus {

    /**
     * @param accessibilityEnabled whether the Accessibility_Engine is currently enabled.
     * @return [CallDetectionStatus.ACTIVE] when enabled (Req 1.7),
     *         [CallDetectionStatus.INACTIVE] otherwise (Req 1.6).
     */
    fun statusFor(accessibilityEnabled: Boolean): CallDetectionStatus =
        if (accessibilityEnabled) CallDetectionStatus.ACTIVE else CallDetectionStatus.INACTIVE
}
