package com.personal.unifiedrecorder.core.policy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

class RoutingMechanismSelectorTest : StringSpec({

    // Feature: unified-call-recorder, Property 9: API-level routing mechanism selection
    // Validates: Requirements 3.3
    "Property 9: API <= 30 uses speakerphone toggle, API >= 31 uses communication device" {
        checkAll(PropTestConfig(iterations = 100), Arb.int(29..40)) { apiLevel ->
            val expected =
                if (apiLevel <= 30) RoutingMechanism.SPEAKERPHONE_TOGGLE
                else RoutingMechanism.COMMUNICATION_DEVICE
            RoutingMechanismSelector.select(apiLevel) shouldBe expected
        }
    }
})
