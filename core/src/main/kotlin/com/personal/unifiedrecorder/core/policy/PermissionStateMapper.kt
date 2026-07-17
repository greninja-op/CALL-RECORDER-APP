package com.personal.unifiedrecorder.core.policy

import com.personal.unifiedrecorder.core.model.PermissionDisplay
import com.personal.unifiedrecorder.core.model.RuntimePermission

/**
 * Maps raw permission grant state plus the device API level into the request set, the
 * denied messaging, the permanently-denied settings directive, and a complete binary
 * status map (Requirements 8.1, 8.3, 8.4, 8.5).
 *
 * Pure and framework-independent: the Android adapter supplies plain sets of granted and
 * permanently-denied permissions; all decisions are deterministic here.
 */
object PermissionStateMapper {

    /** The permissions always required on every supported API level. */
    private val BASE_REQUIRED: Set<RuntimePermission> = setOf(
        RuntimePermission.RECORD_AUDIO,
        RuntimePermission.READ_PHONE_STATE,
        RuntimePermission.MODIFY_AUDIO_SETTINGS
    )

    /**
     * The set of runtime permissions required for a given API level: [BASE_REQUIRED] plus
     * POST_NOTIFICATIONS if and only if [apiLevel] is 33 or higher (Requirements 8.1, 8.5).
     */
    fun requiredSet(apiLevel: Int): Set<RuntimePermission> =
        if (apiLevel >= 33) BASE_REQUIRED + RuntimePermission.POST_NOTIFICATIONS else BASE_REQUIRED

    /** The complete result of mapping permission state for a launch/permission view. */
    data class PermissionState(
        /** The permissions required for this API level (Req 8.1, 8.5). */
        val required: Set<RuntimePermission>,
        /** Complete map of every required permission to GRANTED / NOT_GRANTED (Req 8.5). */
        val statusMap: Map<RuntimePermission, PermissionDisplay>,
        /** Required permissions that are not yet granted and must be requested (Req 8.1). */
        val requestSet: Set<RuntimePermission>,
        /** Required permissions that are currently denied (not granted) (Req 8.3). */
        val deniedPermissions: Set<RuntimePermission>,
        /** One message per denied permission, each naming that permission (Req 8.3). */
        val deniedMessages: List<String>,
        /**
         * True when at least one required permission is permanently denied
         * ("don't ask again"), triggering the system-settings directive
         * regardless of the other permissions (Req 8.4).
         */
        val settingsDirective: Boolean
    )

    /**
     * Compute the [PermissionState] for the current situation.
     *
     * @param apiLevel the device API level.
     * @param granted the set of currently granted permissions.
     * @param permanentlyDenied the set of permissions the user denied with "don't ask again".
     */
    fun map(
        apiLevel: Int,
        granted: Set<RuntimePermission>,
        permanentlyDenied: Set<RuntimePermission> = emptySet()
    ): PermissionState {
        val required = requiredSet(apiLevel)

        val statusMap = required.associateWith { permission ->
            if (permission in granted) PermissionDisplay.GRANTED else PermissionDisplay.NOT_GRANTED
        }

        val requestSet = required - granted
        val deniedPermissions = required.filter { it !in granted }.toSet()
        val deniedMessages = deniedPermissions.map { deniedMessage(it) }
        val settingsDirective = required.any { it in permanentlyDenied }

        return PermissionState(
            required = required,
            statusMap = statusMap,
            requestSet = requestSet,
            deniedPermissions = deniedPermissions,
            deniedMessages = deniedMessages,
            settingsDirective = settingsDirective
        )
    }

    /** Denied-permission message that identifies the permission by name (Req 8.3). */
    private fun deniedMessage(permission: RuntimePermission): String =
        "${permission.name}: call recording will not function until this permission is granted."
}
