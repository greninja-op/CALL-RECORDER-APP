package com.personal.unifiedrecorder.core.logic

import com.personal.unifiedrecorder.core.model.CallState
import com.personal.unifiedrecorder.core.port.AccessibilityWindowEvent

/**
 * An immutable snapshot of the [CallStateMachine].
 *
 * @property state the current [CallState].
 * @property lastAppliedState the target state of the most recently applied transition, or
 *   `null` if no transition has been applied yet. Used as the debounce reference.
 * @property lastAppliedAtMillis the timestamp of the most recently applied transition, or
 *   `null` if no transition has been applied yet.
 */
data class CallStateSnapshot(
    val state: CallState,
    val lastAppliedState: CallState? = null,
    val lastAppliedAtMillis: Long? = null,
)

/**
 * Pure, deterministic classifier that maps [AccessibilityWindowEvent]s onto [CallState]
 * transitions (Requirements 1.2, 1.3, 1.4, 1.5, 1.8).
 *
 * The machine is a pure state holder: given a current [CallStateSnapshot] and an incoming
 * event it produces the next snapshot with no side effects, no clock access, and no
 * `android.*` dependencies. Timing is derived solely from the event timestamps.
 *
 * Classification rules:
 * - A Target_Dialer package with a class name containing `InCallActivity` or `InCallUI`
 *   maps to [CallState.ACTIVE].
 * - The Target_VoIP_App (`com.whatsapp`) with a class name containing `VoipActivity` maps
 *   to [CallState.ACTIVE].
 * - A dismissal of one of those target windows while the call is [CallState.ACTIVE] maps to
 *   [CallState.ENDED].
 * - Events from non-target packages leave the current state unchanged.
 *
 * Debounce rule: an event matching an already-applied transition (same target state) that
 * arrives within one second (`<= 1000ms`) of the prior matching event is ignored; matching
 * events separated by more than one second are applied.
 */
object CallStateMachine {

    /** Duplicate matching transitions within this window (inclusive) are debounced. */
    const val DEBOUNCE_WINDOW_MILLIS: Long = 1_000L

    /** Recognized Target_Dialer packages (Requirement 1.2). */
    val TARGET_DIALER_PACKAGES: Set<String> = setOf(
        "com.google.android.dialer",
        "com.android.incallui",
        "com.samsung.android.incallui",
    )

    /** The recognized Target_VoIP_App package (Requirement 1.3). */
    const val TARGET_VOIP_PACKAGE: String = "com.whatsapp"

    private val DIALER_CLASS_SUBSTRINGS: List<String> = listOf("InCallActivity", "InCallUI")
    private const val VOIP_CLASS_SUBSTRING: String = "VoipActivity"

    /** A snapshot with no transitions applied yet, seeded with [state]. */
    fun initial(state: CallState = CallState.ENDED): CallStateSnapshot = CallStateSnapshot(state)

    /**
     * Reduce [snapshot] with [event], returning the next snapshot.
     *
     * Returns [snapshot] unchanged when the event does not qualify for a transition
     * (non-target package, or a dismissal while not active) or when the event is a debounced
     * duplicate of the already-applied transition.
     */
    fun reduce(snapshot: CallStateSnapshot, event: AccessibilityWindowEvent): CallStateSnapshot {
        val candidate = classify(event, snapshot.state) ?: return snapshot

        // Debounce: ignore a matching duplicate transition within the debounce window.
        val lastAt = snapshot.lastAppliedAtMillis
        if (candidate == snapshot.lastAppliedState &&
            lastAt != null &&
            event.timestampMillis - lastAt <= DEBOUNCE_WINDOW_MILLIS
        ) {
            return snapshot
        }

        return snapshot.copy(
            state = candidate,
            lastAppliedState = candidate,
            lastAppliedAtMillis = event.timestampMillis,
        )
    }

    /**
     * Determine the target [CallState] an event would transition to, or `null` when the event
     * does not qualify for any transition given the [currentState].
     */
    fun classify(event: AccessibilityWindowEvent, currentState: CallState): CallState? {
        if (!isTargetActiveWindow(event)) return null
        return when {
            event.windowDismissed && currentState == CallState.ACTIVE -> CallState.ENDED
            event.windowDismissed -> null // dismissal while not active leaves state unchanged
            else -> CallState.ACTIVE
        }
    }

    /** True when [event] originates from a recognized target in-call window. */
    private fun isTargetActiveWindow(event: AccessibilityWindowEvent): Boolean {
        val pkg = event.packageName
        val cls = event.className
        return when {
            pkg in TARGET_DIALER_PACKAGES -> DIALER_CLASS_SUBSTRINGS.any { cls.contains(it) }
            pkg == TARGET_VOIP_PACKAGE -> cls.contains(VOIP_CLASS_SUBSTRING)
            else -> false
        }
    }
}
