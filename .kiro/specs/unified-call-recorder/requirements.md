# Requirements Document

## Introduction

The Unified Call Recorder is a standalone, sideloaded Android application delivered as a single APK. It combines a call management user interface with an internal Accessibility Engine to capture phone call audio locally on devices running Android 10 (API 29) through Android 15 (API 35) and later. Because the app is manually sideloaded rather than distributed through the Play Store, it operates outside Play Store sandboxing constraints and relies on an internal AccessibilityService to monitor system dialer call screens, detect call state transitions, and drive an audio routing loop that captures call audio through the device microphone channel.

The application records call audio to its isolated external files directory, presents a dashboard for browsing and playing back recordings, guides the user through manual deployment steps (enabling the Accessibility Service and clearing Restricted Settings), and manages the runtime permissions required for audio capture and telephony state access. Because call recording is regulated by consent laws that vary by jurisdiction, the application presents a consent notice before enabling recording.

## Glossary

- **Application**: The complete sideloaded Android call recorder APK, including UI, telephony triggers, and audio routing logic within one package UID.
- **Accessibility_Engine**: The internal AccessibilityService that monitors target system call screens and reports call state transitions.
- **Audio_Router**: The subsystem that configures audio capture using AudioSource.VOICE_COMMUNICATION and coordinates with AudioManager and TelecomManager to route call audio to the hardware microphone channel.
- **Recorder**: The subsystem that captures the audio stream and writes it to a file.
- **Dashboard**: The Jetpack Compose UI screen that lists recordings and provides playback controls.
- **Onboarding_Guide**: The UI flow that instructs the user through manual deployment steps such as enabling the Accessibility Service and clearing Restricted Settings.
- **Permission_Manager**: The subsystem that requests and tracks the status of runtime permissions.
- **Recording**: A single audio file produced from one captured call, stored with a timestamped name.
- **Recording_Directory**: The application's isolated external files directory returned by context.getExternalFilesDir(null).
- **Consent_Notice**: The disclaimer informing the user of the legal responsibility to comply with call recording consent laws.
- **Target_Dialer**: A recognized system in-call user interface package, specifically com.google.android.dialer, com.android.incallui, or com.samsung.android.incallui.
- **Call_State**: The state of a monitored phone call, one of incoming, outgoing, active, or ended.

## Requirements

### Requirement 1: Detect Call State Transitions

**User Story:** As a user, I want the app to detect when a call starts and ends, so that recording can begin and end automatically without manual intervention.

#### Acceptance Criteria

1. WHILE the Accessibility_Engine is enabled, THE Accessibility_Engine SHALL monitor window state change events for each Target_Dialer package (com.google.android.dialer, com.android.incallui, com.samsung.android.incallui).
2. WHEN a window state change event originates from a Target_Dialer package with a class name matching InCallActivity or InCallUI, THE Accessibility_Engine SHALL set the Call_State to active within 1 second of receiving the event.
3. WHEN the InCallActivity or InCallUI window of a Target_Dialer package is dismissed or closed, THE Accessibility_Engine SHALL set the Call_State to ended within 1 second of receiving the event.
4. WHEN a window state change event originates from a package that is not one of the Target_Dialer packages, THE Accessibility_Engine SHALL ignore the event and leave the current Call_State unchanged.
5. IF the Accessibility_Engine is disabled, THEN THE Application SHALL display a status indicating that call detection is inactive.
6. IF a window state change event matching an already-applied Call_State transition is received again within 1 second of the prior matching event, THEN THE Accessibility_Engine SHALL ignore the duplicate event and retain the current Call_State.

### Requirement 2: Automatic Recording Lifecycle

**User Story:** As a user, I want recording to start and stop automatically with the call, so that I capture the full conversation without touching the phone during the call.

#### Acceptance Criteria

1. WHEN the Call_State transitions to active AND the RECORD_AUDIO permission is granted AND no capture is in progress, THE Recorder SHALL begin capturing call audio within 1 second of the transition.
2. WHEN the Call_State transitions to ended AND a capture is in progress, THE Recorder SHALL stop capturing call audio within 1 second and finalize the Recording so that the stored Recording is playable and its duration equals the captured audio length.
3. IF the Call_State transitions to active AND the RECORD_AUDIO permission is not granted, THEN THE Application SHALL skip capture and record a status entry indicating the missing permission.
4. WHILE a capture is in progress, THE Application SHALL display an active recording indicator that is visible for the entire duration of the capture and is removed within 1 second after the capture stops.
5. IF the Recorder fails to begin or continue capturing call audio, THEN THE Application SHALL stop the capture attempt, discard any partial Recording, and record a status entry indicating the capture failure.
6. IF the Call_State transitions to active AND a capture is already in progress, THEN THE Recorder SHALL continue the existing capture and skip starting a new capture.

### Requirement 3: Audio Routing for Capture

**User Story:** As a user, I want the app to capture call audio despite Android restrictions on the voice call stream, so that recordings contain audible conversation.

#### Acceptance Criteria

1. WHEN the Recorder begins capturing call audio, THE Audio_Router SHALL configure the capture session using AudioSource.VOICE_COMMUNICATION within 2 seconds of the capture request.
2. WHEN a capture session is configured, THE Audio_Router SHALL coordinate with AudioManager and TelecomManager to route call audio to the hardware microphone channel within 2 seconds of session configuration.
3. IF configuring the capture session with AudioSource.VOICE_COMMUNICATION fails, THEN THE Audio_Router SHALL attempt the configured fallback capture path using AudioManager speakerphone routing for a maximum of 3 attempts and SHALL record a status entry indicating success or failure for each attempt.
4. IF a configured capture path produces an audio stream that contains only silence for a continuous period of 3 seconds, THEN THE Audio_Router SHALL treat that path as failed and attempt the next configured capture path.
5. IF all capture paths fail to produce an audible audio stream within 6 seconds of the initial capture request, THEN THE Application SHALL record a status entry indicating that capture could not be established for the call and SHALL retain all prior status entries without discarding them.

### Requirement 4: Persist Recordings to Storage

**User Story:** As a user, I want recordings saved as files on my device, so that I can access and keep them locally.

#### Acceptance Criteria

1. WHEN the Recorder finalizes a Recording, THE Recorder SHALL write the complete captured audio to a single file in the Recording_Directory and close the file within 5 seconds of finalization.
2. WHEN the Recorder creates a Recording file, THE Recorder SHALL name the file using a timestamp representing the capture start time with precision to the second, and SHALL guarantee that each file name is unique within the Recording_Directory by appending a distinguishing suffix when a name collision would otherwise occur.
3. THE Recorder SHALL encode each Recording using a single Application-configured audio format selected from WAV, MP4, or AAC, and SHALL use AAC when no format has been configured.
4. WHILE writing a Recording to storage, THE Recorder SHALL perform all file input and output on a background coroutine dispatcher without blocking the main thread.
5. IF a write to the Recording_Directory fails, THEN THE Application SHALL record a status entry indicating the storage failure, SHALL retain any successfully written portion of the file, and SHALL NOT delete any previously saved Recordings.
6. IF the Recording_Directory is unavailable or has insufficient free space to store the Recording at the moment writing begins, THEN THE Recorder SHALL abort the write, record a status entry indicating the storage-unavailable condition, and preserve any in-memory Recording data.

### Requirement 5: Recordings Dashboard and Playback

**User Story:** As a user, I want a dashboard listing my recordings with playback controls, so that I can review captured calls.

#### Acceptance Criteria

1. WHEN the Dashboard is displayed, THE Dashboard SHALL list each Recording present in the Recording_Directory, ordered by recording timestamp from most recent to oldest.
2. WHEN the Dashboard lists a Recording, THE Dashboard SHALL display that Recording's timestamp showing the date and time of day at which the Recording was captured.
3. WHEN the user selects a Recording for playback, THE Dashboard SHALL begin audio playback of the selected Recording within 2 seconds of the selection.
4. WHILE a Recording is playing, THE Dashboard SHALL provide a control to pause playback, a control to resume playback from the paused position, and a control to stop playback and return playback position to the start of the Recording.
5. WHERE the Recording_Directory contains no Recording files, THE Dashboard SHALL display an empty state message indicating that no recordings are available.
6. IF playback of a selected Recording cannot start or fails before completion, THEN THE Dashboard SHALL stop the playback attempt, display an error message indicating that the Recording could not be played, and retain the Recording in the list.

### Requirement 6: Runtime Permission Handling

**User Story:** As a user, I want the app to request the permissions it needs, so that recording and call detection function correctly.

#### Acceptance Criteria

1. WHEN the Application is launched and any of the RECORD_AUDIO, READ_PHONE_STATE, or MODIFY_AUDIO_SETTINGS runtime permissions are not currently granted, THE Permission_Manager SHALL request each not-granted permission from among these three.
2. WHEN the user grants a requested permission, THE Permission_Manager SHALL update the displayed status of that permission to granted within 1 second of receiving the grant result.
3. IF a required runtime permission is denied, THEN THE Application SHALL display a message that identifies the denied permission by name and states that call recording will not function until the permission is granted.
4. IF a required runtime permission is denied with the "don't ask again" option selected such that the system no longer shows the permission prompt, THEN THE Application SHALL display a message directing the user to enable the permission from the system app settings screen.
5. WHEN the user opens the permission status view, THE Permission_Manager SHALL display each of the RECORD_AUDIO, READ_PHONE_STATE, and MODIFY_AUDIO_SETTINGS permissions with its current grant status shown as one of exactly two values: granted or not granted.

### Requirement 7: Onboarding and Manual Deployment Guidance

**User Story:** As a user sideloading the app, I want step-by-step setup guidance, so that I can enable the Accessibility Service and clear Restricted Settings.

#### Acceptance Criteria

1. WHEN the Application is launched AND the Accessibility_Engine is not enabled, THE Onboarding_Guide SHALL display step-by-step instructions for enabling the Accessibility Service within 2 seconds of the onboarding screen becoming visible.
2. IF Restricted Settings block enabling the Accessibility Service on Android 13 or higher, THEN THE Onboarding_Guide SHALL display step-by-step instructions for clearing Restricted Settings via the app info screen.
3. WHEN the user selects an instruction to open a system settings screen, THE Application SHALL open the corresponding Android settings screen within 2 seconds.
4. IF the corresponding Android settings screen cannot be opened when the user selects an instruction, THEN THE Application SHALL display an error message indicating the settings screen could not be opened AND SHALL retain the displayed onboarding instructions.
5. WHEN the Accessibility_Engine transitions to the enabled state, THE Onboarding_Guide SHALL display the setup as complete within 2 seconds of detecting the state change.

### Requirement 8: Consent and Legal Disclaimer

**User Story:** As a user, I want to be informed of my legal responsibility regarding call recording, so that I can comply with consent laws in my jurisdiction.

#### Acceptance Criteria

1. WHEN the Application is launched for the first time and no prior acknowledgment is recorded, THE Application SHALL display the Consent_Notice stating that call recording consent laws vary by jurisdiction and are the user's responsibility, and SHALL keep the Consent_Notice visible until the user acts on it.
2. WHILE the user has not recorded an acknowledgment of the Consent_Notice, THE Application SHALL keep automatic recording disabled.
3. WHEN the user selects the acknowledgment action on the Consent_Notice, THE Application SHALL record the acknowledgment persistently and enable the option to activate automatic recording.
4. IF the user dismisses or declines the Consent_Notice without acknowledging it, THEN THE Application SHALL keep automatic recording disabled and SHALL not record an acknowledgment.
5. WHILE no acknowledgment of the Consent_Notice is recorded, THE Recorder SHALL refrain from capturing call audio.
6. WHEN the Application is launched and a prior acknowledgment is recorded, THE Application SHALL not display the Consent_Notice and SHALL treat the consent as acknowledged.
7. WHEN the user selects the Consent_Notice control on the Dashboard after acknowledgment, THE Application SHALL display the full Consent_Notice content.

### Requirement 9: Platform Compatibility

**User Story:** As a user, I want the app to run across supported Android versions, so that it works on my device.

#### Acceptance Criteria

1. WHEN the Application is installed on a device running an Android version from API level 29 through API level 35 inclusive, THE Application SHALL complete installation and launch to its main screen without displaying an error or terminating unexpectedly.
2. WHERE a device runs an Android version above API level 35, THE Application SHALL execute using the API level 35 compatibility behavior and SHALL launch to its main screen without displaying an error or terminating unexpectedly.
3. IF an attempt is made to install the Application on a device running an Android version below API level 29, THEN THE Application SHALL prevent installation and SHALL present an indication that the device's Android version is not supported.
4. WHEN the Application launches on any device running API level 29 through API level 35, THE Application SHALL make its core call-recording features available for use.
