package com.personal.unifiedrecorder.adapter

import android.content.Context
import com.personal.unifiedrecorder.core.consent.AcknowledgmentStore

/**
 * [AcknowledgmentStore] persisting the Consent_Notice acknowledgment in [android.content.SharedPreferences]
 * so it survives relaunches (Requirements 10.3, 10.6). The pure-core `ConsentGate` depends only on this
 * narrow interface, keeping consent logic JVM-testable.
 */
class SharedPreferencesAcknowledgmentStore(
    context: Context
) : AcknowledgmentStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isAcknowledged(): Boolean = prefs.getBoolean(KEY_ACKNOWLEDGED, false)

    override fun setAcknowledged() {
        prefs.edit().putBoolean(KEY_ACKNOWLEDGED, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "consent_store"
        const val KEY_ACKNOWLEDGED = "consent_acknowledged"
    }
}
