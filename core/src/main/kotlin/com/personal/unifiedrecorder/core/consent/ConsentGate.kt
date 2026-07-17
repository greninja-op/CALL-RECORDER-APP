package com.personal.unifiedrecorder.core.consent

/**
 * Persistence port for the user's Consent_Notice acknowledgment.
 *
 * The flag survives launches (persisted by the adapter); the core depends only on this narrow
 * interface so the gate is fully testable on the JVM with an in-memory fake.
 */
interface AcknowledgmentStore {
    /** @return true when a prior acknowledgment of the Consent_Notice has been recorded. */
    fun isAcknowledged(): Boolean

    /** Persist that the user has acknowledged the Consent_Notice. */
    fun setAcknowledged()
}

/**
 * Pure gate over the persisted Consent_Notice acknowledgment flag.
 *
 * Requirements:
 * - 10.2 / 10.5: while no acknowledgment is recorded, automatic recording stays disabled and the
 *   Recorder refrains from capturing call audio.
 * - 10.3: acknowledging records the acknowledgment persistently and permits enabling automatic
 *   recording.
 * - 10.4: dismissing/declining leaves the acknowledgment unset and keeps recording disabled.
 * - 10.6: when a prior acknowledgment exists, the notice is not shown on launch and consent is
 *   treated as given.
 *
 * No `android.*` dependencies — persistence is delegated to [AcknowledgmentStore].
 */
class ConsentGate(private val store: AcknowledgmentStore) {

    /**
     * Whether automatic call-audio recording is permitted.
     *
     * Req 10.2 / 10.5: false until the Consent_Notice has been acknowledged.
     */
    fun recordingPermitted(): Boolean = store.isAcknowledged()

    /**
     * Record the user's acknowledgment of the Consent_Notice.
     *
     * Req 10.3: persists the acknowledgment so recording may subsequently be enabled.
     */
    fun acknowledge() {
        store.setAcknowledged()
    }

    /**
     * Handle the user dismissing/declining the Consent_Notice.
     *
     * Req 10.4: intentionally does not touch the persisted flag, so recording stays disabled.
     */
    fun dismiss() {
        // No-op by design: declining must not record an acknowledgment.
    }

    /**
     * Whether the Consent_Notice must be shown on launch.
     *
     * Req 10.6: a prior acknowledgment suppresses the notice.
     */
    fun shouldShowNoticeOnLaunch(): Boolean = !store.isAcknowledged()

    /**
     * Whether consent is treated as given.
     *
     * Req 10.6: a prior acknowledgment is treated as consent having been given.
     */
    fun consentGiven(): Boolean = store.isAcknowledged()
}
