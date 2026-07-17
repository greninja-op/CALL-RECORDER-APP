package com.personal.unifiedrecorder.core.consent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll

/**
 * In-memory fake of [AcknowledgmentStore] for deterministic JVM property tests.
 * The [acknowledged] flag stands in for the persisted acknowledgment across launches.
 */
private class InMemoryAcknowledgmentStore(initialAcknowledged: Boolean = false) : AcknowledgmentStore {
    private var acknowledged: Boolean = initialAcknowledged
    override fun isAcknowledged(): Boolean = acknowledged
    override fun setAcknowledged() { acknowledged = true }
}

/** Models the user's interaction with the Consent_Notice. */
private enum class ConsentInteraction { ACKNOWLEDGE, DISMISS }

class ConsentGatePropertyTest : StringSpec({

    // Feature: unified-call-recorder, Property 43: Consent gate blocks recording until acknowledged. For any application state in which no acknowledgment of the Consent_Notice is recorded, the ConsentGate SHALL report that recording is not permitted and the Recorder SHALL refrain from capturing call audio.
    "Property 43: consent gate blocks recording until acknowledged" {
        checkAll(PropTestConfig(iterations = 100), Arb.boolean()) { alreadyAcknowledged ->
            val store = InMemoryAcknowledgmentStore(initialAcknowledged = alreadyAcknowledged)
            val gate = ConsentGate(store)

            // Recording is permitted exactly when an acknowledgment is recorded; with none,
            // recording must be blocked.
            gate.recordingPermitted() shouldBe alreadyAcknowledged
            if (!alreadyAcknowledged) {
                gate.recordingPermitted() shouldBe false
            }
        }
    }

    // Feature: unified-call-recorder, Property 44: Acknowledgment enables recording; decline keeps it disabled. For any consent interaction, acknowledging SHALL set the persisted acknowledgment and permit enabling automatic recording, while dismissing or declining SHALL leave the acknowledgment unset and keep recording disabled.
    "Property 44: acknowledgment enables recording; decline keeps it disabled" {
        checkAll(PropTestConfig(iterations = 100), Arb.enum<ConsentInteraction>()) { interaction ->
            // Start from a fresh, unacknowledged state so the effect of the interaction is isolated.
            val store = InMemoryAcknowledgmentStore(initialAcknowledged = false)
            val gate = ConsentGate(store)

            gate.recordingPermitted() shouldBe false

            when (interaction) {
                ConsentInteraction.ACKNOWLEDGE -> gate.acknowledge()
                ConsentInteraction.DISMISS -> gate.dismiss()
            }

            val expectedPermitted = interaction == ConsentInteraction.ACKNOWLEDGE
            gate.recordingPermitted() shouldBe expectedPermitted
            store.isAcknowledged() shouldBe expectedPermitted
        }
    }

    // Feature: unified-call-recorder, Property 45: Prior acknowledgment hides the notice and treats consent as given. For any launch in which a prior acknowledgment is recorded, the Consent_Notice SHALL not be shown on launch and the ConsentGate SHALL treat consent as acknowledged.
    "Property 45: prior acknowledgment hides the notice and treats consent as given" {
        checkAll(PropTestConfig(iterations = 100), Arb.boolean()) { priorAcknowledgment ->
            val store = InMemoryAcknowledgmentStore(initialAcknowledged = priorAcknowledgment)
            val gate = ConsentGate(store)

            // Notice is shown on launch iff there is no prior acknowledgment.
            gate.shouldShowNoticeOnLaunch() shouldBe !priorAcknowledgment
            // Consent is treated as given iff a prior acknowledgment exists.
            gate.consentGiven() shouldBe priorAcknowledgment
        }
    }
})
