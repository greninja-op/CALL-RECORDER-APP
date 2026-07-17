package com.personal.unifiedrecorder.adapter

import android.Manifest
import com.personal.unifiedrecorder.core.model.RuntimePermission

/**
 * Bridges the framework-independent core [RuntimePermission] enum to the concrete
 * Android [Manifest.permission] string constants and back.
 *
 * Keeping this mapping in one small helper preserves the core's `android.*`-free
 * invariant: only adapters know the platform permission identifiers.
 */
internal object RuntimePermissions {

    /** The Android manifest permission string for a core [RuntimePermission]. */
    fun androidPermission(permission: RuntimePermission): String = when (permission) {
        RuntimePermission.RECORD_AUDIO -> Manifest.permission.RECORD_AUDIO
        RuntimePermission.READ_PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
        RuntimePermission.MODIFY_AUDIO_SETTINGS -> Manifest.permission.MODIFY_AUDIO_SETTINGS
        RuntimePermission.POST_NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
    }

    /** The core [RuntimePermission] for an Android manifest permission string, or null if unmapped. */
    fun fromAndroidPermission(androidPermission: String): RuntimePermission? = when (androidPermission) {
        Manifest.permission.RECORD_AUDIO -> RuntimePermission.RECORD_AUDIO
        Manifest.permission.READ_PHONE_STATE -> RuntimePermission.READ_PHONE_STATE
        Manifest.permission.MODIFY_AUDIO_SETTINGS -> RuntimePermission.MODIFY_AUDIO_SETTINGS
        Manifest.permission.POST_NOTIFICATIONS -> RuntimePermission.POST_NOTIFICATIONS
        else -> null
    }
}
