# Requirements Document

## Introduction

The Unified Call Recorder is a standalone, sideloaded Android application delivered as a single APK (minSdk = 29, targetSdk = 35). It combines a call management user interface with an internal Accessibility Engine to automatically capture both stock cellular phone call audio and WhatsApp VoIP call audio locally on devices running Android 10 (API 29) through Android 15 (API 35) and later.

The Application uses a Hybrid Adaptive Architecture: at the start of each call it detects the device environment and dynamically chooses between a low-level rooted interception path (stealth capture from the internal audio mix) and an unrooted microphone loop that uses automated loudspeaker routing. On the unrooted path it applies a real-time Digital Signal Processing pipeline and a Smart Silence storage optimization. Because the app is manually sideloaded rather than distributed through the Play Store, it operates outside Play Store sandboxing constraints and relies on an internal AccessibilityService to monitor system dialer and WhatsApp VoIP call screens.

To ensure absolute data preservation, the Application isolates its files within a dedicated, user-selected public shared storage directory bound through the Storage Access Framework. Every Recording is accompanied by a Metadata_Companion JSON file holding call metadata, the audio processing profile, and user annotations. All recordings and their companion files survive Application uninstallation indefinitely, and the Application re-binds to this exact directory upon reinstallation to restore historical records, sort filters, and annotations without scanning unrelated device media or music files.

## Glossary

- **Application**: The complete sideloaded Android call recorder APK, including UI, telephony triggers, and audio routing logic within one package UID.
- **Accessibility_Engine**: The internal AccessibilityService that monitors target system call screens and reports call state transitions.
- **Audio_Router**: The subsystem that checks superuser availability and executes the appropriate routing path, either rooted stealth capture or unrooted loudspeaker capture using AudioSource.VOICE_COMMUNICATION.
- **DSP_Pipeline**: The Digital Signal Processing subsystem that applies Automatic Gain Control and a low-pass filter to raw PCM audio buffers in real time, active only on the unrooted capture path.
- **Recorder**: The subsystem that captures the audio stream, applies real-time processing where applicable, and serializes the audio and its Metadata_Companion to storage.
- **Dashboard**: The Jetpack Compose UI screen that lists recordings, separates them by Call_Type and Direction_Type filter controls, provides text search, and provides playback and annotation controls.
- **Onboarding_Guide**: The UI flow that instructs the user through the mandatory initial directory picker, enabling the Accessibility Service, and clearing Restricted Settings.
- **Permission_Manager**: The subsystem that requests and tracks the status of runtime permissions.
- **Recording**: A single audio file produced from one captured call, stored with a timestamped name indicating its Call_Type and Direction_Type.
- **Target_Directory**: The persistent public shared storage directory chosen by the user on initial launch (for example, a dedicated UnifiedCallRecorder folder inside the device's public Recordings directory), bound through the Storage Access Framework as a persisted directory tree URI, where files survive Application deletion.
- **Metadata_Companion**: A lightweight structured JSON file written concurrently with the audio Recording into the Target_Directory, sharing the exact same base filename as the audio file, containing the Call_Type, Direction_Type, Identity_Context, capture start timestamp, call duration, an audio_profile section, and a user_annotations section.
- **Consent_Notice**: The disclaimer informing the user of the legal responsibility to comply with call recording consent laws.
- **Target_Dialer**: A recognized system in-call user interface package, specifically com.google.android.dialer, com.android.incallui, or com.samsung.android.incallui, whose in-call window class name contains InCallActivity or InCallUI.
- **Target_VoIP_App**: The recognized VoIP calling application, specifically com.whatsapp, whose in-call window class name contains VoipActivity.
- **Target_Package**: Any package that is either a Target_Dialer or the Target_VoIP_App. These are the only packages whose window events may change the Call_State.
- **Call_State**: The state of a monitored phone call, whether a cellular call or a WhatsApp VoIP call, one of incoming, outgoing, active, or ended.
- **Call_Type**: The origin protocol of a captured call, classified explicitly as either CELLULAR or WHATSAPP.
- **Direction_Type**: The operational vector of a captured call, classified strictly as either INCOMING or OUTGOING.
- **Identity_Context**: The resolved caller identity for a captured call, consisting of the phone number and the contact display name as presented on the in-call screen (which reflects the device's address book match when one exists), with UNKNOWN used for any field that cannot be resolved.
- **Execution_Mode**: The runtime capture path chosen by the Audio_Router for a call, either ROOTED_STEALTH or UNROOTED_LOUDSPEAKER.
- **Silence_Threshold**: The baseline amplitude level below which audio frames are treated as inactive speech for the Smart Silence optimization.

## Requirements

### Requirement 1: Detect Call State Transitions

**User Story:** As a user, I want the app to detect when a cellular call or a WhatsApp call starts and ends, so that recording can begin and end automatically without manual intervention.

#### Acceptance Criteria

1. WHILE the Accessibility_Engine is enabled, THE Accessibility_Engine SHALL monitor window state change events for each Target_Package (com.google.android.dialer, com.android.incallui, com.samsung.android.incallui, and com.whatsapp).
2. WHEN a window state change event originates from a Target_Dialer package with a class name containing InCallActivity or InCallUI, THE Accessibility_Engine SHALL set the Call_State to active within 1 second of receiving the event.
3. WHEN a window state change event originates from the Target_VoIP_App (com.whatsapp) with a class name containing VoipActivity, THE Accessibility_Engine SHALL set the Call_State to active within 1 second of receiving the event.
4. WHEN the InCallActivity or InCallUI window of a Target_Dialer package, or the VoipActivity window of the Target_VoIP_App, is dismissed or closed, THE Accessibility_Engine SHALL set the Call_State to ended within 1 second of receiving the event.
5. WHEN a window state change event originates from a package that is not one of the Target_Package packages, THE Accessibility_Engine SHALL ignore the event and leave the current Call_State unchanged.
6. IF the Accessibility_Engine is disabled, THEN THE Application SHALL display a status indicating that call detection is inactive.
7. WHILE the Accessibility_Engine is enabled, THE Application SHALL display a status indicating that call detection is active.
8. IF a window state change event matching an already-applied Call_State transition is received again within 1 second of the prior matching event, THEN THE Accessibility_Engine SHALL ignore the duplicate event and retain the current Call_State.

### Requirement 2: Automatic Recording Lifecycle

**User Story:** As a user, I want recording to start and stop automatically with the call, so that I capture the full conversation without touching the phone during the call.

#### Acceptance Criteria

1. WHEN the Call_State transitions to active AND the RECORD_AUDIO permission is granted AND no capture is in progress, THE Recorder SHALL begin capturing call audio within 1 second of the transition.
2. WHEN the Call_State transitions to ended AND a capture is in progress, THE Recorder SHALL stop capturing call audio within 1 second and finalize both the audio Recording and its Metadata_Companion so that the stored Recording is playable and its duration equals the captured audio length.
3. IF the Recorder fails to stop or finalize a capture cleanly when the Call_State transitions to ended, THEN THE Recorder SHALL preserve the successfully captured portion of the Recording as a playable file in the Target_Directory rather than discarding it, and SHALL record a status entry indicating the abnormal stop.
4. IF the Call_State transitions to active AND the RECORD_AUDIO permission is not granted, THEN THE Application SHALL skip capture and record a status entry indicating the missing permission.
5. WHILE a capture is in progress, THE Application SHALL display an active recording indicator that is visible for the entire duration of the capture and is removed within 1 second after the capture stops.
6. IF the Recorder fails to begin or continue capturing call audio, THEN THE Application SHALL stop the capture attempt, discard any partial Recording, and record a status entry indicating the capture failure.
7. IF the Call_State transitions to active AND a capture is already in progress, THEN THE Recorder SHALL continue the existing capture and skip starting a new capture.

### Requirement 3: Hybrid Adaptive Audio Routing

**User Story:** As a user, I want the app to capture call audio using the best method my device supports, so that recordings contain audible two-way conversation whether or not my device is rooted.

#### Acceptance Criteria

1. WHEN the Call_State transitions to active, THE Audio_Router SHALL perform an environment check to determine superuser (su) availability and SHALL select the Execution_Mode within 200 milliseconds of the transition.
2. IF superuser access is verified, THEN THE Audio_Router SHALL select ROOTED_STEALTH, launch an out-of-band superuser shell session within 1 second, and capture the internal multi-channel audio mix without altering the device loudspeaker state or forcing open-air microphone capture.
3. IF superuser access is not available, THEN THE Audio_Router SHALL select UNROOTED_LOUDSPEAKER, configure the capture session using AudioSource.VOICE_COMMUNICATION within 2 seconds of the capture request, and route call audio to the hardware microphone channel using AudioManager.isSpeakerphoneOn on devices running API level 30 or lower, or AudioManager.getAvailableCommunicationDevices() to select the phone speaker device followed by AudioManager.setCommunicationDevice() on devices running API level 31 through API level 35 and higher.
4. IF configuring the UNROOTED_LOUDSPEAKER capture session with AudioSource.VOICE_COMMUNICATION fails, THEN THE Audio_Router SHALL attempt the fallback capture path using AudioManager speakerphone routing for a maximum of 3 attempts and SHALL record a status entry indicating success or failure for each attempt.
5. IF a configured UNROOTED_LOUDSPEAKER capture path produces an audio stream that contains only silence for a continuous period of 3 seconds, THEN THE Audio_Router SHALL treat that path as failed and attempt the next configured capture path.
6. WHEN a configured capture path produces an audible audio stream, THE Audio_Router SHALL record a status entry indicating that the capture path succeeded.
7. IF all capture paths fail to produce an audible audio stream within 6 seconds of the initial capture request, THEN THE Application SHALL record a status entry indicating that capture could not be established for the call and SHALL retain all prior status entries without discarding them.
8. WHEN the Audio_Router has selected an Execution_Mode for a call, THE Recorder SHALL record the selected Execution_Mode value (ROOTED_STEALTH or UNROOTED_LOUDSPEAKER) in the audio_profile section of that call's Metadata_Companion.

### Requirement 4: Real-Time DSP Pipeline

**User Story:** As a user recording via the loudspeaker, I want the caller's voice cleaned and balanced, so that recordings are clear and the far party is as audible as I am.

#### Acceptance Criteria

1. WHILE the Execution_Mode is UNROOTED_LOUDSPEAKER and raw PCM audio frames flow through the capture pipeline, THE DSP_Pipeline SHALL apply an Automatic Gain Control algorithm to normalize speech volume and a low-pass filter to attenuate loudspeaker hiss before the frames are written to storage.
2. WHILE the DSP_Pipeline is processing audio, THE DSP_Pipeline SHALL execute all signal-processing calculations on a background high-priority thread separate from the main thread so that the main thread is not blocked.
3. IF the Execution_Mode is ROOTED_STEALTH, THEN THE Recorder SHALL bypass the DSP_Pipeline entirely and preserve the raw captured stream unmodified.
4. WHEN the DSP_Pipeline is applied to a call, THE Recorder SHALL record the applied filter identifier in the audio_profile section of that call's Metadata_Companion.

### Requirement 5: Smart Silence Storage Optimization

**User Story:** As a user, I want long silent stretches trimmed from my recordings, so that files stay small without losing any spoken audio.

#### Acceptance Criteria

1. WHILE the Execution_Mode is UNROOTED_LOUDSPEAKER and a capture is in progress, THE Recorder SHALL analyze the amplitude of audio frame buffers in real time on a background coroutine dispatcher.
2. IF the average amplitude remains below the Silence_Threshold for a continuous period exceeding 5.0 seconds, THEN THE Recorder SHALL pause serialization of audio frames to storage.
3. WHEN the average amplitude rises to or above the Silence_Threshold after serialization has been paused, THE Recorder SHALL resume writing audio frames within 100 milliseconds without corrupting the structure of the output file.
4. WHEN a call concludes, THE Recorder SHALL record the total number of skipped silent seconds in the audio_profile section of that call's Metadata_Companion.

### Requirement 6: Persist Recordings to Public Isolated Storage

**User Story:** As a user, I want recordings and their metadata saved into a specific public folder that survives app uninstallation, and I want the app to connect back to that exact folder if I reinstall it.

#### Acceptance Criteria

1. WHEN the Recorder finalizes a Recording, THE Recorder SHALL write the complete captured audio into the user-selected public Target_Directory using the Storage Access Framework through the persisted directory tree URI (via DocumentsContract and DocumentFile) within 5 seconds of finalization, such that the written file resides in public shared storage and remains on the device if the Application is uninstalled.
2. WHEN the Recorder creates a Recording file, THE Recorder SHALL name the file using the structured convention [CALL_TYPE]_[DIRECTION]_[YYYYMMDD]_[HHMMSS] where CALL_TYPE is CELLULAR or WHATSAPP and DIRECTION is INCOMING or OUTGOING (for example, CELLULAR_INCOMING_20260717_143022.wav or WHATSAPP_OUTGOING_20260717_143210.wav), and SHALL guarantee that each file name is unique within the Target_Directory by appending a distinguishing suffix when a name collision would otherwise occur.
3. WHERE the DSP_Pipeline or the Smart Silence optimization is active for a call, THE Recorder SHALL capture through an AudioRecord PCM pipeline and encode the Recording as WAV; OTHERWISE THE Recorder SHALL encode each Recording using a single Application-configured audio format selected from WAV, MP4, or AAC, and SHALL use AAC when no format has been configured.
4. IF encoding a Recording with the selected audio format fails, THEN THE Recorder SHALL fall back to another supported audio format from among WAV, MP4, or AAC, record a status entry indicating the format fallback, and complete the Recording using the fallback format.
5. WHILE writing a Recording or its Metadata_Companion to storage, including while aborting a write due to an error such as insufficient free space or an unavailable Target_Directory, THE Recorder SHALL perform all Storage Access Framework document writes and stream operations on a background coroutine dispatcher (Dispatchers.IO) without blocking the main thread.
6. IF a write to the Target_Directory fails, THEN THE Application SHALL record a status entry indicating the storage failure, SHALL retain any successfully written portion of the file, and SHALL NOT delete any previously saved Recordings.
7. IF the Target_Directory becomes unavailable or has insufficient free space to store the Recording at the moment writing begins, THEN THE Recorder SHALL abort the write, record a status entry indicating the storage-unavailable condition, and preserve any in-memory Recording data.
8. THE Application SHALL confine its storage read and write operations strictly to the user-selected Target_Directory, and SHALL NOT scan, read, index, or display music files, voice notes, or other external audio content residing outside the bound Target_Directory path.
9. WHEN a captured call concludes, THE Application SHALL determine the Call_Type, the Direction_Type, the Identity_Context, and the call duration by inspecting the telephony state and the accessibility node tree of the in-call window, and THE Recorder SHALL serialize this information together with the audio_profile and user_annotations sections into a Metadata_Companion JSON file written on a background coroutine dispatcher (Dispatchers.IO) into the Target_Directory adjacent to the audio file and sharing the audio file's base filename.
10. IF any element of the Direction_Type or Identity_Context cannot be resolved when the Metadata_Companion is written, THEN THE Recorder SHALL still write the Metadata_Companion using the value UNKNOWN for each unresolved element rather than omitting the file, so that every audio Recording has an accompanying Metadata_Companion.
11. IF writing the Metadata_Companion file fails, THEN THE Application SHALL record a status entry indicating the metadata write failure AND SHALL retain the associated audio Recording in the Target_Directory.

### Requirement 7: Isolated Dashboard Filtering, Playback, and Annotation

**User Story:** As a user, I want a clean dashboard that lets me filter and search my calls and add notes and bookmarks, so that I can organize and review captured calls without clutter from other media files.

#### Acceptance Criteria

1. WHEN the Dashboard is displayed, THE Dashboard SHALL list each Recording present exclusively within the designated Target_Directory, ordered by recording timestamp from most recent to oldest.
2. THE Dashboard SHALL provide filtering and sorting controls allowing the user to query Recordings by Call_Type (ALL, WHATSAPP, or CELLULAR), by Direction_Type (ALL, INCOMING, or OUTGOING), and by a text search string matched against the contact name or phone number.
3. WHEN a Call_Type or Direction_Type filter is active or a text search string is entered, THE Dashboard SHALL display only the Recordings whose Metadata_Companion, or whose file name structural fields when no Metadata_Companion is available, match all active filter and search criteria, and SHALL hide all non-matching Recordings.
4. WHEN the Dashboard renders a Recording entry, THE Dashboard SHALL parse the corresponding Metadata_Companion JSON file and display the contact name, phone number, formatted capture date and time, call direction indicator, and call duration formatted as MM:SS.
5. IF a Recording has no Metadata_Companion file or its Metadata_Companion cannot be parsed, THEN THE Dashboard SHALL display the Recording using the fields derived from its file name and SHALL show a placeholder for each unavailable metadata field rather than hiding the Recording.
6. WHEN the user selects a Recording for playback, THE Dashboard SHALL begin audio playback of the selected Recording within 2 seconds of the selection.
7. WHILE a Recording is playing, THE Dashboard SHALL provide a control to pause playback, a control to resume playback from the paused position, and a control to stop playback and return playback position to the start of the Recording.
8. WHILE a Recording is playing, THE Dashboard SHALL provide an overlay allowing the user to create, edit, or jump to timestamped text bookmarks, and WHEN the user creates or edits a bookmark or category tag, THE Application SHALL save the annotation to the user_annotations section of that Recording's Metadata_Companion on a background coroutine dispatcher (Dispatchers.IO).
9. WHERE the Target_Directory contains no Recording files matching the active filter, THE Dashboard SHALL display an empty state message indicating that no recordings are available for that category.
10. IF playback of a selected Recording cannot start or fails before completion, THEN THE Dashboard SHALL stop the playback attempt, display an error message indicating that the Recording could not be played, and retain the Recording in the list.

### Requirement 8: Runtime Permission Handling

**User Story:** As a user, I want the app to request the permissions it needs, so that recording and call detection function correctly.

#### Acceptance Criteria

1. WHEN the Application is launched and any of its required runtime permissions are not currently granted, THE Permission_Manager SHALL request each not-granted required runtime permission. The required runtime permissions are RECORD_AUDIO, READ_PHONE_STATE, and MODIFY_AUDIO_SETTINGS on all supported API levels, and additionally POST_NOTIFICATIONS on devices running API level 33 or higher.
2. WHEN the user grants a requested permission, THE Permission_Manager SHALL update the displayed status of that permission to granted within 1 second (inclusive of exactly 1 second) of receiving the grant result.
3. IF a required runtime permission is denied, THEN THE Application SHALL display a message that identifies the denied permission by name and states that call recording will not function until the permission is granted.
4. IF a required runtime permission is denied with the "don't ask again" option selected such that the system no longer shows the permission prompt, THEN THE Application SHALL display a message directing the user to enable the permission from the system app settings screen, regardless of the grant status of the other required permissions.
5. WHEN the user opens the permission status view, THE Permission_Manager SHALL display each required runtime permission (RECORD_AUDIO, READ_PHONE_STATE, and MODIFY_AUDIO_SETTINGS, and additionally POST_NOTIFICATIONS on devices running API level 33 or higher) with its current grant status shown as one of exactly two values: granted or not granted.

### Requirement 9: Storage Location Setup and Onboarding Guidance

**User Story:** As a user installing the app for the first time or reinstalling it, I want to be required to pick my storage directory first so the app can save new recordings and pull up my old files and annotations instantly.

#### Acceptance Criteria

1. WHEN the Application is launched for the first time or following a fresh reinstallation with no valid Target_Directory bound, THE Onboarding_Guide SHALL display a mandatory configuration screen requiring the user to designate or retrieve their storage location before presenting the main Dashboard or starting background monitoring.
2. THE Onboarding_Guide SHALL provide an interaction control that triggers the Storage Access Framework directory picker (Intent.ACTION_OPEN_DOCUMENT_TREE), allowing the user to select an existing historical call recording folder or create a new dedicated folder in public shared storage.
3. WHEN the user selects and authorizes a folder path via the directory picker, THE Application SHALL persist the directory tree URI permission across device reboots using contentResolver.takePersistableUriPermission, set that location as the Target_Directory for future writes, and index any pre-existing Recordings within that folder by reading their Metadata_Companion files to restore the historical call history and annotations, all within 2 seconds of authorization.
4. WHEN the Application is launched AND the Accessibility_Engine is not enabled, THE Onboarding_Guide SHALL display step-by-step instructions for enabling the Accessibility Service within 2 seconds of the onboarding screen becoming visible.
5. IF Restricted Settings block enabling the Accessibility Service on Android 13 or higher, THEN THE Onboarding_Guide SHALL display step-by-step instructions for clearing Restricted Settings via the app info screen within 2 seconds of the Application activity regaining window focus (onResume) after the block is detected.
6. WHEN the user selects an instruction to open a system settings screen, THE Application SHALL open the corresponding Android settings screen within 2 seconds.
7. IF the corresponding Android settings screen cannot be opened when the user selects an instruction, THEN THE Application SHALL display an error message indicating the settings screen could not be opened AND SHALL retain the displayed onboarding instructions.
8. WHEN the Accessibility_Engine is detected as enabled AND a valid Target_Directory tree URI is securely bound, THE Onboarding_Guide SHALL display the setup as complete and unlock the main Dashboard within 2 seconds of detecting both conditions.

### Requirement 10: Consent and Legal Disclaimer

**User Story:** As a user, I want to be informed of my legal responsibility regarding call recording, so that I can comply with consent laws in my jurisdiction.

#### Acceptance Criteria

1. WHEN the Application is launched for the first time and no prior acknowledgment is recorded, THE Application SHALL display the Consent_Notice stating that call recording consent laws vary by jurisdiction and are the user's responsibility, and SHALL keep the Consent_Notice visible until the user acts on it.
2. WHILE the user has not recorded an acknowledgment of the Consent_Notice, THE Application SHALL keep automatic recording disabled.
3. WHEN the user selects the acknowledgment action on the Consent_Notice, THE Application SHALL record the acknowledgment persistently and enable the option to activate automatic recording.
4. IF the user dismisses or declines the Consent_Notice without acknowledging it, THEN THE Application SHALL keep automatic recording disabled and SHALL not record an acknowledgment.
5. WHILE no acknowledgment of the Consent_Notice is recorded, THE Recorder SHALL refrain from capturing call audio.
6. WHEN the Application is launched and a prior acknowledgment is recorded, THE Application SHALL not display the Consent_Notice and SHALL treat the consent as acknowledged.
7. WHEN the user selects the Consent_Notice control on the Dashboard after acknowledgment, THE Application SHALL display the full Consent_Notice content.

### Requirement 11: Platform Compatibility

**User Story:** As a user, I want the app to run across supported Android versions, so that it works on my device.

#### Acceptance Criteria

1. WHEN the Application is installed on a device running an Android version from API level 29 through API level 35 inclusive, THE Application SHALL complete installation and launch to its main screen without displaying an error or terminating unexpectedly.
2. WHERE a device runs an Android version above API level 35, THE Application SHALL execute using the API level 35 compatibility behavior and SHALL launch to its main screen without displaying an error or terminating unexpectedly.
3. IF an attempt is made to install the Application on a device running an Android version below API level 29, THEN THE Application SHALL prevent installation and SHALL present an indication that the device's Android version is not supported.
4. WHEN the Application launches on any device running API level 29 through API level 35, THE Application SHALL make its core call-recording features available for use.
