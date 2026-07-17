package com.personal.unifiedrecorder.core.logic

import com.personal.unifiedrecorder.core.model.CallState
import com.personal.unifiedrecorder.core.port.AccessibilityWindowEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 * Property-based tests for [CallStateMachine] (Properties 1, 2, 3).
 * Generators are private to this file; no shared test utilities.
 */
class CallStateMachinePropertyTest : FunSpec({

    val config = PropTestConfig(iterations = 100)

    // ---- Private generators --------------------------------------------------

    // A Target_Dialer event whose class name contains an in-call trigger substring.
    val dialerActiveEvent: Arb<AccessibilityWindowEvent> = arbitrary {
        val pkg = Arb.element(CallStateMachine.TARGET_DIALER_PACKAGES.toList()).bind()
        val sub = Arb.of("InCallActivity", "InCallUI").bind()
        val prefix = Arb.of("", "com.android.", "ui.").bind()
        val suffix = Arb.of("", "Impl", "\$Inner").bind()
        val ts = Arb.long(0L..5_000_000L).bind()
        AccessibilityWindowEvent(pkg, "$prefix$sub$suffix", ts, windowDismissed = false)
    }

    // A Target_VoIP_App event whose class name contains the VoIP trigger substring.
    val voipActiveEvent: Arb<AccessibilityWindowEvent> = arbitrary {
        val prefix = Arb.of("", "com.whatsapp.", "voip.").bind()
        val suffix = Arb.of("", "V2", "\$1").bind()
        val ts = Arb.long(0L..5_000_000L).bind()
        AccessibilityWindowEvent(
            CallStateMachine.TARGET_VOIP_PACKAGE,
            "${prefix}VoipActivity$suffix",
            ts,
            windowDismissed = false,
        )
    }

    // Any target window event that should classify to ACTIVE.
    val targetActiveEvent: Arb<AccessibilityWindowEvent> = Arb.choice(dialerActiveEvent, voipActiveEvent)

    // A dismissal of a target window (should classify to ENDED while active).
    val targetDismissEvent: Arb<AccessibilityWindowEvent> = arbitrary {
        targetActiveEvent.bind().copy(windowDismissed = true)
    }

    // A window event from a package that is NOT a Target_Package.
    val nonTargetEvent: Arb<AccessibilityWindowEvent> = arbitrary {
        val pkg = Arb.of(
            "com.android.chrome",
            "com.example.app",
            "com.google.android.gm",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.foo.bar",
            "android",
        ).bind()
        // Include adversarial class names that contain trigger substrings.
        val cls = Arb.of("", "InCallActivity", "InCallUI", "VoipActivity", "MainActivity").bind()
        val dismissed = Arb.boolean().bind()
        val ts = Arb.long(0L..5_000_000L).bind()
        AccessibilityWindowEvent(pkg, cls, ts, dismissed)
    }

    // ---- Properties ----------------------------------------------------------

    test("Property 1: target window events classify to the correct call state") {
        // Feature: unified-call-recorder, Property 1: Target window events classify to the correct call state
        // Validates: Requirements 1.2, 1.3, 1.4
        checkAll(config, targetActiveEvent) { event ->
            val next = CallStateMachine.reduce(CallStateMachine.initial(CallState.INCOMING), event)
            next.state shouldBe CallState.ACTIVE
        }
        checkAll(config, targetDismissEvent) { event ->
            val active = CallStateMachine.initial(CallState.ACTIVE)
            val next = CallStateMachine.reduce(active, event)
            next.state shouldBe CallState.ENDED
        }
    }

    test("Property 2: non-target events never change call state") {
        // Feature: unified-call-recorder, Property 2: Non-target events never change call state
        // Validates: Requirements 1.5
        checkAll(config, Arb.enum<CallState>(), nonTargetEvent) { prior, event ->
            val snapshot = CallStateSnapshot(
                state = prior,
                lastAppliedState = prior,
                lastAppliedAtMillis = 1_234L,
            )
            CallStateMachine.reduce(snapshot, event) shouldBe snapshot
        }
    }

    test("Property 3: duplicate matching transitions within one second are debounced") {
        // Feature: unified-call-recorder, Property 3: Duplicate matching transitions within one second are debounced
        // Validates: Requirements 1.8
        checkAll(config, targetActiveEvent, Arb.long(0L..3_000L)) { first, deltaMillis ->
            val afterFirst = CallStateMachine.reduce(CallStateMachine.initial(CallState.INCOMING), first)
            val second = first.copy(timestampMillis = first.timestampMillis + deltaMillis)
            val afterSecond = CallStateMachine.reduce(afterFirst, second)

            afterSecond.state shouldBe CallState.ACTIVE
            if (deltaMillis <= CallStateMachine.DEBOUNCE_WINDOW_MILLIS) {
                // Debounced duplicate: snapshot (including timestamp) is retained.
                afterSecond shouldBe afterFirst
            } else {
                // Separated by more than one second: the transition is applied.
                afterSecond.lastAppliedAtMillis shouldBe first.timestampMillis + deltaMillis
            }
        }
    }
})
