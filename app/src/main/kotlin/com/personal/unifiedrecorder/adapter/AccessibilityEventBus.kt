package com.personal.unifiedrecorder.adapter

import com.personal.unifiedrecorder.core.port.AccessibilityWindowEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide conduit between the system-instantiated [UnifiedAccessibilityService]
 * and the [UnifiedAccessibilityEventSource] adapter consumed by the core.
 *
 * The Android framework constructs the accessibility service itself, so the service
 * cannot be handed collaborators through a constructor. This singleton bus lets the
 * service publish window events and enablement state that the (separately constructed)
 * event-source adapter observes.
 *
 * Device-only / manual verification: actual emission is driven by real accessibility
 * events on a device; on the JVM this is just a flow relay.
 */
internal object AccessibilityEventBus {

    // extraBufferCapacity keeps emission non-suspending from the service callback thread.
    private val _events = MutableSharedFlow<AccessibilityWindowEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<AccessibilityWindowEvent> = _events.asSharedFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Publish a window event; drops the event if no buffer capacity remains (best effort). */
    fun publish(event: AccessibilityWindowEvent) {
        _events.tryEmit(event)
    }

    /** Record whether the Accessibility_Engine is currently connected/enabled. */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }
}
