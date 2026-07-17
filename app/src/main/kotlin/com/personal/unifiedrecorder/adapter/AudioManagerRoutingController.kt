package com.personal.unifiedrecorder.adapter

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.personal.unifiedrecorder.core.policy.RoutingMechanism
import com.personal.unifiedrecorder.core.policy.RoutingMechanismSelector
import com.personal.unifiedrecorder.core.port.AudioRoutingController
import com.personal.unifiedrecorder.core.port.RoutingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AudioRoutingController] over [AudioManager] that routes call audio to the built-in loudspeaker
 * for the UNROOTED_LOUDSPEAKER capture path (Requirements 3.2, 3.3, 3.4).
 *
 * The API-appropriate mechanism is chosen by the pure-core [RoutingMechanismSelector]:
 * - API >= 31: [AudioManager.setCommunicationDevice] selecting a `TYPE_BUILTIN_SPEAKER` device.
 * - API <= 30: the deprecated `isSpeakerphoneOn = true` toggle.
 *
 * Device-only / manual verification: actual audio routing behavior can only be confirmed on a
 * device during a live call.
 */
class AudioManagerRoutingController(
    context: Context
) : AudioRoutingController {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override val apiLevel: Int = Build.VERSION.SDK_INT

    // Previous state captured so [restorePreviousRouting] can reverse the change.
    private var previousMode: Int = AudioManager.MODE_NORMAL
    @Suppress("DEPRECATION")
    private var previousSpeakerphoneOn: Boolean = false
    private var didSetCommunicationDevice: Boolean = false

    override suspend fun routeToLoudspeaker(): RoutingResult = withContext(Dispatchers.IO) {
        runCatching {
            previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            when (RoutingMechanismSelector.select(apiLevel)) {
                RoutingMechanism.COMMUNICATION_DEVICE ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        routeViaCommunicationDevice()
                    } else {
                        routeViaSpeakerphoneToggle()
                    }
                RoutingMechanism.SPEAKERPHONE_TOGGLE -> routeViaSpeakerphoneToggle()
            }
        }.getOrElse { t ->
            RoutingResult(success = false, message = t.message ?: "Failed to route to loudspeaker")
        }
    }

    override suspend fun restorePreviousRouting(): Unit = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (didSetCommunicationDevice) {
                    audioManager.clearCommunicationDevice()
                    didSetCommunicationDevice = false
                }
            } else {
                @Suppress("DEPRECATION")
                run { audioManager.isSpeakerphoneOn = previousSpeakerphoneOn }
            }
            audioManager.mode = previousMode
        }
        Unit
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun routeViaCommunicationDevice(): RoutingResult {
        val speaker = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: return RoutingResult(success = false, message = "No built-in speaker communication device available")

        val applied = audioManager.setCommunicationDevice(speaker)
        didSetCommunicationDevice = applied
        return if (applied) {
            RoutingResult(success = true, message = "Routed to built-in speaker via setCommunicationDevice")
        } else {
            RoutingResult(success = false, message = "setCommunicationDevice returned false")
        }
    }

    @Suppress("DEPRECATION")
    private fun routeViaSpeakerphoneToggle(): RoutingResult {
        previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.isSpeakerphoneOn = true
        return RoutingResult(success = true, message = "Routed to loudspeaker via isSpeakerphoneOn")
    }
}
