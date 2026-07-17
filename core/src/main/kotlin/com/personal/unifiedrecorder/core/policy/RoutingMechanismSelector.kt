package com.personal.unifiedrecorder.core.policy

/**
 * The API-appropriate mechanism used to route call audio to the built-in loudspeaker
 * on the unrooted capture path (Requirement 3.3).
 */
enum class RoutingMechanism {
    /** `AudioManager.isSpeakerphoneOn = true` — used on API level 30 and lower. */
    SPEAKERPHONE_TOGGLE,

    /**
     * `AudioManager.getAvailableCommunicationDevices()` + `setCommunicationDevice(...)` —
     * used on API level 31 and higher, where [SPEAKERPHONE_TOGGLE] is deprecated.
     */
    COMMUNICATION_DEVICE
}

/**
 * Chooses the routing mechanism purely as a function of the device API level (Requirement 3.3).
 *
 * The split occurs at API 31: `setSpeakerphoneOn` is deprecated from Android 12, so API >= 31
 * uses communication-device selection while API <= 30 keeps the speakerphone toggle.
 */
object RoutingMechanismSelector {

    /**
     * @param apiLevel the device's Android SDK API level.
     * @return [RoutingMechanism.SPEAKERPHONE_TOGGLE] for API <= 30,
     *         [RoutingMechanism.COMMUNICATION_DEVICE] for API >= 31.
     */
    fun select(apiLevel: Int): RoutingMechanism =
        if (apiLevel <= 30) RoutingMechanism.SPEAKERPHONE_TOGGLE else RoutingMechanism.COMMUNICATION_DEVICE
}
