package com.personal.unifiedrecorder.core.model

import kotlinx.serialization.Serializable

/** The kind of call being captured. */
@Serializable
enum class CallType { CELLULAR, WHATSAPP }

/** The direction of a call relative to the device owner. */
@Serializable
enum class Direction { INCOMING, OUTGOING }

/** The lifecycle state derived from accessibility window events. */
enum class CallState { INCOMING, OUTGOING, ACTIVE, ENDED }

/** The capture strategy selected for a call. */
@Serializable
enum class ExecutionMode { ROOTED_STEALTH, UNROOTED_LOUDSPEAKER }

/** Supported on-disk audio container/encoding formats. */
enum class AudioFormat { WAV, MP4, AAC }

/** Runtime (dangerous / special) permissions the application depends on. */
enum class RuntimePermission { RECORD_AUDIO, READ_PHONE_STATE, MODIFY_AUDIO_SETTINGS, POST_NOTIFICATIONS }

/** Display state of a single permission in the permission UI. */
enum class PermissionDisplay { GRANTED, NOT_GRANTED }

/** Dashboard call-type filter selection. */
enum class CallTypeFilter { ALL, WHATSAPP, CELLULAR }

/** Dashboard direction filter selection. */
enum class DirectionFilter { ALL, INCOMING, OUTGOING }
