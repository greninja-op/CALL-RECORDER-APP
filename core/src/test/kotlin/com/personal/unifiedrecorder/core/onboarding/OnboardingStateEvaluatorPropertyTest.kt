package com.personal.unifiedrecorder.core.onboarding

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.checkAll

class OnboardingStateEvaluatorPropertyTest : StringSpec({

    // Feature: unified-call-recorder, Property 40: Onboarding is required until a directory is bound. For any application state with no valid Target_Directory bound, the OnboardingStateEvaluator SHALL require the mandatory configuration screen, keep the Dashboard locked, and prevent background monitoring from starting.
    "Property 40: onboarding required until a directory is bound" {
        checkAll(PropTestConfig(iterations = 100), Arb.boolean()) { accessibilityEnabled ->
            // No valid Target_Directory bound.
            val state = OnboardingStateEvaluator.evaluate(
                accessibilityEnabled = accessibilityEnabled,
                directoryBound = false
            )

            state.requiresConfiguration shouldBe true
            state.activeScreen shouldBe OnboardingScreen.CONFIGURATION
            state.dashboardUnlocked shouldBe false
            state.monitoringAllowed shouldBe false
            state.setupComplete shouldBe false
        }
    }

    // Feature: unified-call-recorder, Property 42: Onboarding completes exactly when both conditions hold. For any (accessibility-enabled, directory-bound) combination, OnboardingStateEvaluator SHALL report setup complete and unlock the Dashboard if and only if accessibility is enabled AND a valid Target_Directory is bound.
    "Property 42: onboarding completes iff accessibility enabled AND directory bound" {
        checkAll(PropTestConfig(iterations = 100), Arb.boolean(), Arb.boolean()) { accessibilityEnabled, directoryBound ->
            val state = OnboardingStateEvaluator.evaluate(accessibilityEnabled, directoryBound)

            val expectedComplete = accessibilityEnabled && directoryBound

            state.setupComplete shouldBe expectedComplete
            state.dashboardUnlocked shouldBe expectedComplete
            state.monitoringAllowed shouldBe expectedComplete
            state.requiresConfiguration shouldBe !expectedComplete
            state.activeScreen shouldBe if (expectedComplete) OnboardingScreen.DASHBOARD else OnboardingScreen.CONFIGURATION
        }
    }
})
