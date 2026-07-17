package com.personal.unifiedrecorder.core.policy

import com.personal.unifiedrecorder.core.model.ExecutionMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.PropTestConfig
import io.kotest.property.checkAll
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean

class ExecutionModeSelectorTest : StringSpec({

    // Feature: unified-call-recorder, Property 8: Execution mode selection
    // Validates: Requirements 3.1, 3.2, 3.3
    "Property 8: selects ROOTED_STEALTH iff superuser available, else UNROOTED_LOUDSPEAKER" {
        checkAll(PropTestConfig(iterations = 100), Arb.boolean()) { superuserAvailable ->
            val expected =
                if (superuserAvailable) ExecutionMode.ROOTED_STEALTH else ExecutionMode.UNROOTED_LOUDSPEAKER
            ExecutionModeSelector.select(superuserAvailable) shouldBe expected
        }
    }
})
