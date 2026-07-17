package com.personal.unifiedrecorder.adapter

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.personal.unifiedrecorder.core.logic.AccessibilityStatus
import com.personal.unifiedrecorder.core.logic.CallDetectionStatus
import com.personal.unifiedrecorder.core.port.AccessibilityEventSource
import com.personal.unifiedrecorder.core.port.AccessibilityWindowEvent
import kotlinx.coroutines.flow.Flow

/**
 * [AccessibilityEventSource] adapter that exposes the window events published by
 * [UnifiedAccessibilityService] via [AccessibilityEventBus].
 *
 * [enabled] reflects whether this app's accessibility service is currently enabled in
 * system settings, cross-checked against the live connection flag the service maintains.
 *
 * Device-only / manual verification: real event flow and the settings-enabled state can
 * only be validated on a device with the service toggled on.
 */
class UnifiedAccessibilityEventSource(
    private val context: Context
) : AccessibilityEventSource {

    override fun events(): Flow<AccessibilityWindowEvent> = AccessibilityEventBus.events

    override val enabled: Boolean
        get() = AccessibilityEventBus.enabled.value || isServiceEnabledInSettings()

    /**
     * The user-visible call-detection status (Requirements 1.6, 1.7), derived through the
     * pure-core [AccessibilityStatus] mapping from the current [enabled] flag.
     */
    fun detectionStatus(): CallDetectionStatus = AccessibilityStatus.statusFor(enabled)

    private fun isServiceEnabledInSettings(): Boolean {
        val expectedComponent = "${context.packageName}/${UnifiedAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val accessibilityManager =
            context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val accessibilityEnabled = accessibilityManager?.isEnabled == true
        if (!accessibilityEnabled) return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }
}
