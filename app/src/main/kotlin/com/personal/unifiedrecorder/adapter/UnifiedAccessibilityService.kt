package com.personal.unifiedrecorder.adapter

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.personal.unifiedrecorder.core.port.AccessibilityWindowEvent

/**
 * Internal [AccessibilityService] that monitors Target_Package in-call windows and
 * surfaces raw window events to the core through [AccessibilityEventBus] (Requirement 1.1).
 *
 * The service intentionally does no call-state classification: that lives in the pure-core
 * `CallStateMachine`. It only maps the platform [AccessibilityEvent] into the plain-data
 * [AccessibilityWindowEvent] and applies a lightweight dismissal heuristic.
 *
 * Device-only / manual verification: window-state-changed delivery, package/class values,
 * and dismissal timing can only be exercised on a real device with the service enabled.
 */
class UnifiedAccessibilityService : AccessibilityService() {

    /**
     * The package+class of the most recent in-call target window. Used by the dismissal
     * heuristic: when the foreground window moves to a different package (or the same
     * target package reports a non-in-call window), the previously active in-call window
     * is treated as dismissed.
     */
    private var activeTargetPackage: String? = null
    private var activeTargetClass: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityEventBus.setEnabled(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()
        val eventTime = event.eventTime

        // Dismissal heuristic: an active in-call target window is considered dismissed
        // once a window from a different package (or a non-in-call class of the same
        // target) becomes the foreground window.
        val previousActive = activeTargetPackage
        if (previousActive != null && previousActive != packageName) {
            AccessibilityEventBus.publish(
                AccessibilityWindowEvent(
                    packageName = previousActive,
                    className = activeTargetClass.orEmpty(),
                    timestampMillis = eventTime,
                    windowDismissed = true
                )
            )
            activeTargetPackage = null
            activeTargetClass = null
        }

        // Track the current window as the potentially active in-call target. The core
        // decides whether it is truly a Target_Dialer / Target_VoIP_App in-call window.
        if (isPotentialInCallWindow(className)) {
            activeTargetPackage = packageName
            activeTargetClass = className
        }

        AccessibilityEventBus.publish(
            AccessibilityWindowEvent(
                packageName = packageName,
                className = className,
                timestampMillis = eventTime,
                windowDismissed = false
            )
        )
    }

    override fun onInterrupt() {
        // No-op: no ongoing feedback to interrupt.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityEventBus.setEnabled(false)
        activeTargetPackage = null
        activeTargetClass = null
        return super.onUnbind(intent)
    }

    private fun isPotentialInCallWindow(className: String): Boolean =
        className.contains("InCallActivity") ||
            className.contains("InCallUI") ||
            className.contains("VoipActivity")
}
