# Implementation Plan: Unified Call Recorder

## Overview

This plan implements the Unified Call Recorder using the hexagonal (ports-and-adapters) architecture from the design. Work is deliberately **front-loaded** so that every deterministic decision lives in the pure-Kotlin `:core` JVM module and can be compiled and property-tested locally with `./gradlew :core:test` — no Android device required.

The sequence is:

1. Gradle multi-module scaffolding (root, `settings.gradle.kts`, wrapper, `:core` JVM library, `:app` Android skeleton).
2. All pure-Kotlin `:core` data models, ports, and logic components, each paired with its property-based tests (kotest property testing) and example unit tests, all runnable on the JVM.
3. Only after the core is complete and green: Android adapters, Compose UI, manifest/accessibility config, and app wiring. These must compile against `compileSdk 36` so `./gradlew :app:assembleDebug` can be attempted, but their runtime verification is **device-only / manual** as noted per task.

Implementation language: **Kotlin** (as specified by the design). Property tests use **kotest property testing** (`io.kotest:kotest-property`), a minimum of **100 iterations** per property, **one property = one property test**, each carrying the tag comment:
`// Feature: unified-call-recorder, Property {number}: {property_text}`

## Tasks

- [x] 1. Configure Gradle multi-module project and wrapper
  - [x] 1.1 Create the Gradle project skeleton
    - Create `settings.gradle.kts` declaring modules `:core` and `:app`; create root `build.gradle.kts` with the Kotlin/Android/kotest plugin versions in a version catalog (`gradle/libs.versions.toml`).
    - Generate the Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) so builds run via `./gradlew` on Windows with JDK 21 (Android Studio JBR).
    - Create `:core` as a plain Kotlin/JVM library module (`org.jetbrains.kotlin.jvm`) with `kotlin("test")` and `io.kotest:kotest-runner-junit5` + `io.kotest:kotest-property` test dependencies and JUnit5 platform enabled.
    - Create `:app` as an Android application module skeleton (`com.android.application`, `compileSdk = 36`, `minSdk = 29`, `targetSdk = 35`, JDK 21, Jetpack Compose + Material 3 enabled) that `implementation(project(":core"))`.
    - Verify with `./gradlew projects` and `./gradlew :core:compileKotlin`.
    - _Requirements: 11.1, 11.2, 11.4_

- [x] 2. Establish core data models and ports
  - [x] 2.1 Implement core data models and enums
    - In `:core`, implement all enums and data classes from the design Data Models section: `CallType`, `Direction`, `CallState`, `ExecutionMode`, `AudioFormat`, `RuntimePermission`, `PermissionDisplay`, `IdentityContext`, `AudioProfile`, `Bookmark`, `UserAnnotations`, `MetadataCompanion`, `ParsedFilename`, `RecordingEntry`, `DashboardFilterState`, `CallTypeFilter`, `DirectionFilter`, `CaptureConfig`, and the `StatusEntry` sealed hierarchy.
    - No `android.*` imports — Kotlin stdlib only.
    - _Requirements: 3.8, 5.4, 6.2, 6.9, 6.10, 7.4, 7.8, 8.1, 8.5_

  - [x] 2.2 Define port interfaces
    - Implement the port interfaces over plain data types: `AccessibilityEventSource` (+ `AccessibilityWindowEvent`), `SuperuserProbe`, `AudioCaptureDevice` (+ `ByteSink`), `AudioRoutingController` (+ `RoutingResult`), `DocumentStore` (+ `StoredDocument`), `MediaPlaybackController` (+ `PlaybackResult`), `Clock`, `PermissionChecker`, `StatusLog`.
    - Ports use only plain data; no `android.*` types.
    - _Requirements: 1.1, 3.1, 3.3, 6.1, 6.5, 7.6, 8.5_

- [x] 3. Implement call-state detection (CallStateMachine)
  - [x] 3.1 Implement CallStateMachine
    - Classify `AccessibilityWindowEvent`s into `CallState` using the Target_Dialer/Target_VoIP_App package + class-name substring rules (`InCallActivity`/`InCallUI`, `VoipActivity`); ignore non-target packages; apply the 1-second duplicate-transition debounce; map dismissal events to `ENDED`.
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.8_

  - [x]* 3.2 Write property test: target events classify to correct state
    - **Property 1: Target window events classify to the correct call state**
    - **Validates: Requirements 1.2, 1.3, 1.4**
    - Uses `Arb<AccessibilityWindowEvent>` over target packages with/without trigger substrings; min 100 iterations.

  - [x]* 3.3 Write property test: non-target events never change state
    - **Property 2: Non-target events never change call state**
    - **Validates: Requirements 1.5**

  - [x]* 3.4 Write property test: duplicate transitions debounced
    - **Property 3: Duplicate matching transitions within one second are debounced**
    - **Validates: Requirements 1.8**

- [x] 4. Implement WAV byte writer
  - [x] 4.1 Implement WavByteWriter
    - Produce a valid WAV header + 16-bit PCM payload deterministically; expose reported duration = frames / sample rate and header data length = payload byte count.
    - _Requirements: 2.2, 6.3_

  - [x]* 4.2 Write property test: finalized WAV duration equals captured length
    - **Property 7: Finalized WAV duration equals captured length**
    - **Validates: Requirements 2.2**
    - Uses `Arb<ShortArray>` PCM frames (silence, full-scale, DC-offset, noise); min 100 iterations.

- [x] 5. Implement execution-mode and routing policy
  - [x] 5.1 Implement ExecutionModeSelector
    - Map superuser availability to `ExecutionMode` (available → `ROOTED_STEALTH`, otherwise `UNROOTED_LOUDSPEAKER`).
    - _Requirements: 3.1, 3.2, 3.3_

  - [x]* 5.2 Write property test: execution mode selection
    - **Property 8: Execution mode selection**
    - **Validates: Requirements 3.1, 3.2, 3.3**

  - [x] 5.3 Implement RoutingMechanismSelector (API-level policy)
    - Pure function selecting the routing mechanism by API level: speakerphone toggle for API ≤ 30, communication-device selection for API ≥ 31.
    - _Requirements: 3.3_

  - [x]* 5.4 Write property test: API-level routing mechanism selection
    - **Property 9: API-level routing mechanism selection**
    - **Validates: Requirements 3.3**
    - Uses `Arb.int(29..40)`; min 100 iterations.

- [x] 6. Implement DSP processor
  - [x] 6.1 Implement DspProcessor (AGC + low-pass) with rooted bypass
    - Apply AGC gain toward a configured target level (with 16-bit clamp) and a single-pole low-pass over PCM frames; provide a bypass path that returns raw bytes unchanged when `ExecutionMode` is `ROOTED_STEALTH`.
    - _Requirements: 4.1, 4.3, 4.4_

  - [x]* 6.2 Write property test: DSP output bounds and normalization
    - **Property 15: DSP output stays in bounds and normalizes level**
    - **Validates: Requirements 4.1**
    - Uses `Arb<ShortArray>` PCM frames; min 100 iterations.

  - [x]* 6.3 Write property test: rooted stealth bypasses DSP (identity)
    - **Property 16: Rooted stealth bypasses DSP (identity)**
    - **Validates: Requirements 4.3**

- [x] 7. Implement Smart Silence state machine
  - [x] 7.1 Implement SmartSilenceStateMachine
    - Amplitude/frame-count gate that pauses serialization only after average amplitude stays strictly below `Silence_Threshold` for a continuous period exceeding 5.0s, resumes when amplitude rises to/above threshold, and counts total skipped silent seconds.
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [x]* 7.2 Write property test: pause only after more than five continuous seconds below threshold
    - **Property 17: Smart Silence pauses only after more than five continuous seconds below threshold**
    - **Validates: Requirements 5.2, 5.3**
    - Uses amplitude-over-time timelines `Arb<List<Pair<amplitude, durationMs>>>`; min 100 iterations.

  - [x]* 7.3 Write property test: skipped-silence accounting is exact
    - **Property 19: Skipped-silence accounting is exact**
    - **Validates: Requirements 5.4**

  - [x]* 7.4 Write property test: WAV valid across arbitrary pause/resume sequences
    - **Property 18: WAV output remains structurally valid across arbitrary pause/resume sequences**
    - **Validates: Requirements 5.3**
    - Drives `WavByteWriter` with pause/resume sequences from `SmartSilenceStateMachine`; asserts data-chunk length is a whole multiple of frame size and equals bytes written; min 100 iterations.

- [x] 8. Implement filename codec
  - [x] 8.1 Implement FilenameCodec
    - Encode/parse `[CALL_TYPE]_[DIRECTION]_[YYYYMMDD]_[HHMMSS]` with `.wav/.mp4/.aac` extension per the grammar; append a distinguishing suffix when a name collides with an existing-name set.
    - _Requirements: 6.2, 7.5_

  - [x]* 8.2 Write property test: filename round-trip and uniqueness
    - **Property 20: Filename round-trip and uniqueness**
    - **Validates: Requirements 6.2, 7.5**
    - Uses `Arb<ParsedFilename>` + existing-name sets; min 100 iterations.

- [x] 9. Implement audio-format policy
  - [x] 9.1 Implement AudioFormatPolicy
    - Select `WAV` when DSP or Smart Silence is active; otherwise the configured format, defaulting to `AAC`; on failure select a distinct supported format from {WAV, MP4, AAC} and emit a `FormatFallback` status entry.
    - _Requirements: 6.3, 6.4_

  - [x]* 9.2 Write property test: audio format selection policy
    - **Property 21: Audio format selection policy**
    - **Validates: Requirements 6.3**

  - [x]* 9.3 Write property test: format fallback selects a distinct supported format
    - **Property 22: Format fallback selects a distinct supported format**
    - **Validates: Requirements 6.4**
    - Uses failing-format subsets of {WAV, MP4, AAC}; min 100 iterations.

- [x] 10. Implement metadata codec
  - [x] 10.1 Implement MetadataCodec
    - Serialize/deserialize `MetadataCompanion` to/from the JSON schema in the design; write `UNKNOWN` for unresolved `Direction`/`Identity_Context` fields rather than omitting them; derive the companion file name as the audio base name with `.json`.
    - _Requirements: 6.9, 6.10, 7.4, 7.8_

  - [x]* 10.2 Write property test: metadata round-trip and companion adjacency
    - **Property 26: Metadata round-trip and companion adjacency**
    - **Validates: Requirements 6.9**
    - Uses `Arb<MetadataCompanion>`; min 100 iterations.

  - [x]* 10.3 Write property test: unresolved fields fall back to UNKNOWN and file always written
    - **Property 27: Unresolved metadata fields fall back to UNKNOWN and the file is always written**
    - **Validates: Requirements 6.10**

  - [x]* 10.4 Write property test: annotation edits round-trip through the companion
    - **Property 34: Annotation edits round-trip through the companion**
    - **Validates: Requirements 7.8**

- [x] 11. Implement dashboard filter and formatting
  - [x] 11.1 Implement DashboardFilter and duration formatter
    - Filter/search predicate over `RecordingEntry` by Call_Type/Direction/text (matched against contact name or phone number, using metadata when present else filename-derived fields), sorted newest-first, with empty-state detection; include an `MM:SS` duration formatter/parser.
    - _Requirements: 7.1, 7.3, 7.4, 7.5, 7.9_

  - [x]* 11.2 Write property test: dashboard ordering and source isolation
    - **Property 29: Dashboard ordering and source isolation**
    - **Validates: Requirements 7.1**
    - Uses `Arb<RecordingEntry>` lists; min 100 iterations.

  - [x]* 11.3 Write property test: filter/search soundness, completeness, and empty state
    - **Property 30: Filter and search predicate soundness and completeness (including empty state)**
    - **Validates: Requirements 7.3, 7.9**
    - Uses `Arb<RecordingEntry>` lists + `Arb<DashboardFilterState>`; min 100 iterations.

  - [x]* 11.4 Write property test: missing metadata renders from filename with placeholders
    - **Property 32: Missing metadata renders from filename with placeholders and is never hidden**
    - **Validates: Requirements 7.5**

  - [x]* 11.5 Write property test: duration formatting round-trips as MM:SS
    - **Property 31: Duration formatting round-trips as MM:SS**
    - **Validates: Requirements 7.4**

- [x] 12. Implement playback state model
  - [x] 12.1 Implement PlaybackStateModel
    - Pure model of play/pause/resume/stop transitions and position handling (resume from paused position, reset to 0 on stop) and an error state for failed playback, driven by `MediaPlaybackController` results.
    - _Requirements: 7.7, 7.10_

  - [x]* 12.2 Write property test: playback state transitions
    - **Property 33: Playback state transitions**
    - **Validates: Requirements 7.7**

  - [x]* 12.3 Write property test: playback failure surfaces an error and retains the entry
    - **Property 35: Playback failure surfaces an error and retains the entry**
    - **Validates: Requirements 7.10**

- [x] 13. Implement permission-state mapper
  - [x] 13.1 Implement PermissionStateMapper
    - Compute the required-permission set per API level ({RECORD_AUDIO, READ_PHONE_STATE, MODIFY_AUDIO_SETTINGS} plus POST_NOTIFICATIONS iff API ≥ 33), the request set (required minus granted), denied-permission messaging, permanently-denied settings directive, and the complete GRANTED/NOT_GRANTED status map.
    - _Requirements: 8.1, 8.3, 8.4, 8.5_

  - [x]* 13.2 Write property test: required-permission request set
    - **Property 36: Required-permission request set**
    - **Validates: Requirements 8.1**
    - Uses `Arb.int(29..40)` + grant maps; min 100 iterations.

  - [x]* 13.3 Write property test: denied-permission messaging names exactly the denied permissions
    - **Property 37: Denied-permission messaging names exactly the denied permissions**
    - **Validates: Requirements 8.3**

  - [x]* 13.4 Write property test: permanently-denied triggers a settings directive regardless of others
    - **Property 38: Permanently-denied triggers a settings directive regardless of others**
    - **Validates: Requirements 8.4**

  - [x]* 13.5 Write property test: permission status is a complete binary mapping
    - **Property 39: Permission status is a complete binary mapping**
    - **Validates: Requirements 8.5**

- [x] 14. Implement onboarding evaluator
  - [x] 14.1 Implement OnboardingStateEvaluator
    - Compute onboarding completion from (accessibility enabled, directory bound): require the mandatory configuration screen and keep the Dashboard locked / monitoring off until a valid `Target_Directory` is bound; report complete and unlock iff both conditions hold.
    - _Requirements: 9.1, 9.8_

  - [x]* 14.2 Write property test: onboarding required until a directory is bound
    - **Property 40: Onboarding is required until a directory is bound**
    - **Validates: Requirements 9.1**

  - [x]* 14.3 Write property test: onboarding completes exactly when both conditions hold
    - **Property 42: Onboarding completes exactly when both conditions hold**
    - **Validates: Requirements 9.8**

- [x] 15. Implement consent gate
  - [x] 15.1 Implement ConsentGate
    - Gate automatic recording on a persisted acknowledgment flag: block recording until acknowledged; acknowledging enables recording; dismiss/decline leaves it disabled; a prior acknowledgment hides the notice and treats consent as given.
    - _Requirements: 10.2, 10.3, 10.4, 10.5, 10.6_

  - [x]* 15.2 Write property test: consent gate blocks recording until acknowledged
    - **Property 43: Consent gate blocks recording until acknowledged**
    - **Validates: Requirements 10.2, 10.5**

  - [x]* 15.3 Write property test: acknowledgment enables recording; decline keeps it disabled
    - **Property 44: Acknowledgment enables recording; decline keeps it disabled**
    - **Validates: Requirements 10.3, 10.4**

  - [x]* 15.4 Write property test: prior acknowledgment hides the notice and treats consent as given
    - **Property 45: Prior acknowledgment hides the notice and treats consent as given**
    - **Validates: Requirements 10.6**

- [x] 16. Implement orchestration test fakes
  - [x] 16.1 Implement in-memory fakes for ports
    - Build JVM fakes used by orchestration property tests: `FakeDocumentStore`, `FakeClock`, `FakeStatusLog`, `FakeSuperuserProbe`, `FakeAudioCaptureDevice`, `FakeAudioRoutingController`, `FakeMediaPlaybackController` in the `:core` test source set.
    - _Requirements: 2.1, 3.4, 6.6, 6.7_

- [x] 17. Implement capture-path orchestrator
  - [x] 17.1 Implement capture-begin guard and lifecycle
    - Drive the capture lifecycle from `CallState`: begin a new capture iff `ACTIVE` AND RECORD_AUDIO granted AND no capture in progress; continue existing capture on repeated `ACTIVE`; skip + log `PermissionMissing` when ungranted; on `ENDED` finalize, and on abnormal finalize preserve the captured portion as playable and log `CaptureStopped(abnormal=true)`; on begin/continue failure stop, discard the partial, and log capture-failure.
    - _Requirements: 2.1, 2.3, 2.4, 2.6, 2.7_

  - [x]* 17.2 Write property test: capture-begin guard
    - **Property 4: Capture-begin guard**
    - **Validates: Requirements 2.1, 2.4, 2.7**
    - Uses fakes from 16.1; min 100 iterations.

  - [x]* 17.3 Write property test: abnormal finalize preserves the captured portion
    - **Property 5: Abnormal finalize preserves the captured portion**
    - **Validates: Requirements 2.3**

  - [x]* 17.4 Write property test: capture-begin/continue failure discards the partial
    - **Property 6: Capture-begin/continue failure discards the partial**
    - **Validates: Requirements 2.6**

  - [x] 17.5 Implement ordered fallback, silence failover, and path logging
    - Implement bounded (≤ 3) fallback attempts with exactly one `PathAttempt` per attempt, stop on first success; treat a path as failed and fail over on ≥ 3s continuous silence; log exactly one `PathSucceeded` on first audible audio; on all paths failing within the 6s budget append `CaptureUnestablished` while keeping the status log append-only.
    - _Requirements: 3.4, 3.5, 3.6, 3.7_

  - [x]* 17.6 Write property test: fallback retry is bounded and fully logged
    - **Property 10: Fallback retry is bounded and fully logged**
    - **Validates: Requirements 3.4**
    - Uses fallback-outcome sequences; min 100 iterations.

  - [x]* 17.7 Write property test: continuous silence triggers path failover
    - **Property 11: Continuous silence triggers path failover**
    - **Validates: Requirements 3.5**

  - [x]* 17.8 Write property test: audible stream logs path success exactly once
    - **Property 12: Audible stream logs path success exactly once**
    - **Validates: Requirements 3.6**

  - [x]* 17.9 Write property test: exhausted paths log capture-unestablished with append-only history
    - **Property 13: Exhausted paths log capture-unestablished with append-only status history**
    - **Validates: Requirements 3.7**

  - [x] 17.10 Implement finalize: metadata/storage guards and directory indexing
    - Write the `Metadata_Companion` (recording `execution_mode`, `dsp_filter_id` present iff `UNROOTED_LOUDSPEAKER`, `skipped_silent_seconds`) via `DocumentStore`; abort before writing when the directory is unavailable/low on space (log storage-unavailable, keep in-memory data); on mid-write failure log `StorageFailure`, retain the written portion, never delete other recordings; on metadata-write failure log `MetadataWriteFailure` and retain the audio; confine listing to the bound tree; index existing companions to restore history.
    - _Requirements: 3.8, 4.4, 6.6, 6.7, 6.8, 6.11, 9.3_

  - [x]* 17.11 Write property test: audio profile records execution mode and DSP consistently
    - **Property 14: Audio profile records the execution mode and DSP filter consistently**
    - **Validates: Requirements 3.8, 4.4**

  - [x]* 17.12 Write property test: storage-write failure is non-destructive
    - **Property 23: Storage-write failure is non-destructive**
    - **Validates: Requirements 6.6**

  - [x]* 17.13 Write property test: storage-unavailable abort guard preserves in-memory data
    - **Property 24: Storage-unavailable abort guard preserves in-memory data**
    - **Validates: Requirements 6.7**

  - [x]* 17.14 Write property test: storage access is confined to the bound directory
    - **Property 25: Storage access is confined to the bound directory**
    - **Validates: Requirements 6.8**

  - [x]* 17.15 Write property test: metadata-write failure retains the audio recording
    - **Property 28: Metadata-write failure retains the audio recording**
    - **Validates: Requirements 6.11**

  - [x]* 17.16 Write property test: directory indexing restores history from companions
    - **Property 41: Directory indexing restores history from companions**
    - **Validates: Requirements 9.3**
    - Uses `FakeDocumentStore` seeded with companion files; min 100 iterations.

- [x] 18. Cover core EXAMPLE criteria and edge cases
  - [x]* 18.1 Write example/edge unit tests for the core
    - Example-based tests for EXAMPLE-classified criteria and boundary cases: empty PCM frame, single-sample frame, exactly-5.0s vs just-over-5.0s silence boundary, exactly-1s duplicate-event boundary, maximum filename collision suffixing, duration exactly 59:59, empty recording list.
    - _Requirements: 1.6, 1.7, 2.5, 8.2, 10.1_

- [x] 19. Checkpoint - core JVM tests pass
  - Run `./gradlew :core:test`. Ensure all property and unit tests pass. Ask the user if questions arise.

- [x] 20. Configure the Android app module (manifest and service config)
  - [x] 20.1 Author manifest, permissions, and accessibility-service configuration
    - Declare `RECORD_AUDIO`, `READ_PHONE_STATE`, `MODIFY_AUDIO_SETTINGS`, and `POST_NOTIFICATIONS` (API 33+) permissions; declare the foreground service for the recording indicator; register the `UnifiedAccessibilityService` with its `accessibility-service` XML config scoped to the Target_Package packages; set `minSdk 29`/`targetSdk 35`/`compileSdk 36`.
    - Verification note: must compile with `./gradlew :app:assembleDebug`; runtime accessibility behavior is device-only/manual.
    - _Requirements: 1.1, 8.1, 11.1, 11.2, 11.3, 11.4_

- [x] 21. Implement Android port adapters (device-only runtime verification)
  - [x] 21.1 Implement AccessibilityEventSource adapter (UnifiedAccessibilityService)
    - `AccessibilityService` emitting `AccessibilityWindowEvent`s to the core; expose enabled state and accessibility-active/inactive status text.
    - Verification note: compiles against SDK; window-event behavior verified device-only/manual.
    - _Requirements: 1.1, 1.6, 1.7_

  - [x] 21.2 Implement audio capture adapters (AudioRecord + MediaRecorder)
    - `AudioRecordCaptureDevice` streaming PCM frames for the DSP/Smart-Silence path and `MediaRecorderCaptureDevice` for encoded/rooted paths, implementing `AudioCaptureDevice`; run capture off the main thread.
    - Verification note: real capture is device-only/manual.
    - _Requirements: 2.1, 2.2, 4.2, 5.1, 6.3_

  - [x] 21.3 Implement AudioRoutingController adapter
    - API-aware routing: `isSpeakerphoneOn` for API ≤ 30; `getAvailableCommunicationDevices()` + `setCommunicationDevice()` for API 31–35+; restore previous routing after the call.
    - Verification note: real routing is device-only/manual.
    - _Requirements: 3.2, 3.3, 3.4_

  - [x] 21.4 Implement DocumentStore SAF adapter
    - Implement `DocumentStore` using `DocumentFile`/`DocumentsContract`: `bind` via `takePersistableUriPermission`, create/read/write documents, list recordings confined to the bound tree, report free bytes; all I/O on `Dispatchers.IO`.
    - Verification note: SAF persistence/uninstall survival/reinstall rebind is device-only/manual.
    - _Requirements: 6.1, 6.5, 6.8, 6.9, 9.2, 9.3_

  - [x] 21.5 Implement MediaPlaybackController adapter
    - Implement `MediaPlaybackController` over `MediaPlayer` (play/pause/resume/stop, position, error result).
    - Verification note: playback verified device-only/manual.
    - _Requirements: 7.6, 7.7, 7.10_

  - [x] 21.6 Implement SuperuserProbe, PermissionChecker, Clock, and StatusLog adapters
    - `ShellSuperuserProbe` via `Runtime.exec("su")` off the main thread; `AndroidPermissionChecker` (grant status + shouldOpenSettings); system `Clock`; a `StatusLog` implementation surfacing entries to the UI without mutating history.
    - Verification note: `su` probe verified device-only/manual.
    - _Requirements: 3.1, 3.2, 8.2, 8.5_

- [x] 22. Implement Compose UI (Material 3)
  - [x] 22.1 Implement ConsentScreen and view-state wiring
    - Render the `Consent_Notice` with acknowledge/dismiss actions driven by `ConsentGate`; expose full-content view for the Dashboard consent control after acknowledgment.
    - Verification note: structural checks via Compose tests; visual verification manual.
    - _Requirements: 10.1, 10.7_

  - [x] 22.2 Implement OnboardingScreen and SAF picker launcher
    - Mandatory directory-picker launcher (`ACTION_OPEN_DOCUMENT_TREE`), accessibility-enable guidance, Restricted-Settings guidance (Android 13+), and settings deep links with an error state when a settings screen cannot open; driven by `OnboardingStateEvaluator`.
    - Verification note: settings intents/Restricted-Settings flow are device-only/manual.
    - _Requirements: 9.1, 9.2, 9.4, 9.5, 9.6, 9.7, 9.8_

  - [x] 22.3 Implement PermissionScreen
    - Render each required permission's GRANTED/NOT_GRANTED state and denied/settings directives from `PermissionStateMapper`; wire the runtime permission request flow.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

  - [x] 22.4 Implement DashboardScreen (list, filters, playback, annotations)
    - Recording list ordered newest-first, Call_Type/Direction filters and text search, playback transport (play/pause/resume/stop), timestamped bookmark/category-tag overlay saved on `Dispatchers.IO`, and empty-state message; driven by `DashboardFilter`, `PlaybackStateModel`, and `MetadataCodec`.
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.7, 7.8, 7.9_

- [x] 23. Wire the application together
  - [x] 23.1 Wire Application, service, dependency graph, and recording indicator
    - Construct the object graph binding adapters to core components; start/stop background monitoring gated by onboarding + consent; connect the `CapturePathOrchestrator` to the accessibility event flow; show/remove the active-recording foreground indicator tied to capture lifecycle.
    - _Requirements: 2.5, 9.1, 9.8, 10.2, 10.5, 11.4_

- [x] 24. Add app-level tests (device-aware)
  - [x]* 24.1 Write Compose UI structural tests
    - Assert filter/search controls exist and are wired, the SAF picker launches `ACTION_OPEN_DOCUMENT_TREE`, the Dashboard consent control shows full content after acknowledgment, and the empty-state message renders for an empty filtered list.
    - _Requirements: 7.2, 7.9, 9.2, 10.7_

  - [x]* 24.2 Write integration/smoke tests
    - Instrumented smoke tests for install/launch across API 29–35, above-35 compatibility, and below-29 rejection where installable on AVDs/devices; SAF persistence rebind where automatable.
    - Verification note: remaining behavior covered by the design's device-only manual checklist.
    - _Requirements: 6.1, 6.5, 11.1, 11.2, 11.3, 11.4_

- [x] 25. Checkpoint - build the app and confirm green
  - Run `./gradlew :core:test` and attempt `./gradlew :app:assembleDebug`. Ensure the core suite passes and the app compiles against `compileSdk 36`. Note that device-dependent adapter/UI behavior requires the manual verification checklist. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Tasks 1–19 are fully buildable and testable on the JVM with `./gradlew :core:test` and require no Android device — they are front-loaded for unattended execution.
- Tasks 20–25 are Android-only: they must compile against the Android SDK (`compileSdk 36`) so `./gradlew :app:assembleDebug` can be attempted, but their runtime behavior is verified device-only/manual as annotated per task.
- Each property test carries the tag comment `// Feature: unified-call-recorder, Property {number}: {property_text}`, uses kotest property testing, and runs a minimum of 100 iterations.
- Each task references specific requirement clauses and, where applicable, the exact correctness property from the design for traceability.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1"] },
    { "id": 2, "tasks": ["2.2"] },
    { "id": 3, "tasks": ["3.1", "4.1", "5.1", "5.3", "6.1", "7.1", "8.1", "9.1", "10.1", "11.1", "12.1", "13.1", "14.1", "15.1", "16.1"] },
    { "id": 4, "tasks": ["3.2", "3.3", "3.4", "4.2", "5.2", "5.4", "6.2", "6.3", "7.2", "7.3", "7.4", "8.2", "9.2", "9.3", "10.2", "10.3", "10.4", "11.2", "11.3", "11.4", "11.5", "12.2", "12.3", "13.2", "13.3", "13.4", "13.5", "14.2", "14.3", "15.2", "15.3", "15.4", "17.1"] },
    { "id": 5, "tasks": ["17.2", "17.3", "17.4", "17.5"] },
    { "id": 6, "tasks": ["17.6", "17.7", "17.8", "17.9", "17.10"] },
    { "id": 7, "tasks": ["17.11", "17.12", "17.13", "17.14", "17.15", "17.16", "18.1"] },
    { "id": 8, "tasks": ["20.1"] },
    { "id": 9, "tasks": ["21.1", "21.2", "21.3", "21.4", "21.5", "21.6", "22.1", "22.2", "22.3", "22.4"] },
    { "id": 10, "tasks": ["23.1"] },
    { "id": 11, "tasks": ["24.1", "24.2"] }
  ]
}
```
