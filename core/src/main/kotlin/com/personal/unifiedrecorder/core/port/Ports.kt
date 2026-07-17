package com.personal.unifiedrecorder.core.port

import com.personal.unifiedrecorder.core.model.CaptureConfig
import com.personal.unifiedrecorder.core.model.RuntimePermission
import com.personal.unifiedrecorder.core.model.StatusEntry
import kotlinx.coroutines.flow.Flow

/**
 * Ports: narrow interfaces over every Android/OS capability the core needs.
 *
 * All ports use only plain data types (primitives, [ByteArray], [ShortArray],
 * strings, and core data classes) — never `android.*` types. This is the
 * invariant that keeps the core JVM-testable.
 *
 * Suspending functions denote background (`Dispatchers.IO`) work; the core does
 * not assume a particular thread, but the adapters honor the coroutine
 * dispatcher contracts described in the requirements.
 */

// ---------------------------------------------------------------------------
// Accessibility (Requirement 1)
// ---------------------------------------------------------------------------

/** A raw window event surfaced by the accessibility service adapter. */
data class AccessibilityWindowEvent(
    val packageName: String,
    val className: String,
    val timestampMillis: Long,
    val windowDismissed: Boolean
)

interface AccessibilityEventSource {
    /** Emits raw window events; the adapter does no filtering beyond what the platform provides. */
    fun events(): Flow<AccessibilityWindowEvent>
    val enabled: Boolean
}

// ---------------------------------------------------------------------------
// Superuser probe (Requirement 3.1 / 3.2)
// ---------------------------------------------------------------------------

interface SuperuserProbe {
    /** Returns true if an `su` shell is available. The adapter runs off the main thread. */
    suspend fun isSuperuserAvailable(): Boolean
}

// ---------------------------------------------------------------------------
// Audio capture (Requirements 2, 3, 6.3)
// ---------------------------------------------------------------------------

interface AudioCaptureDevice {
    /** Streams raw PCM 16-bit frames for the AudioRecord path (DSP / Smart Silence active). */
    fun pcmFrames(config: CaptureConfig): Flow<ShortArray>

    /** Encoded capture (MediaRecorder / rooted) writing to a sink; returns the final byte length. */
    suspend fun captureEncoded(config: CaptureConfig, sink: ByteSink): Long

    suspend fun stop()
}

/** A destination for bytes produced by capture or metadata writing. */
interface ByteSink {
    suspend fun write(bytes: ByteArray, offset: Int, length: Int)
    suspend fun close()
    suspend fun abort()
}

// ---------------------------------------------------------------------------
// Audio routing (Requirement 3.3 / 3.4)
// ---------------------------------------------------------------------------

/** Outcome of an audio-routing operation. */
data class RoutingResult(
    val success: Boolean,
    val message: String? = null
)

interface AudioRoutingController {
    val apiLevel: Int

    /** Route call audio to the built-in loudspeaker using the API-appropriate mechanism. */
    suspend fun routeToLoudspeaker(): RoutingResult

    suspend fun restorePreviousRouting()
}

// ---------------------------------------------------------------------------
// Storage via SAF (Requirements 6, 9)
// ---------------------------------------------------------------------------

/** A document discovered under the bound Target_Directory tree. */
data class StoredDocument(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long
)

interface DocumentStore {
    suspend fun isBound(): Boolean
    suspend fun bind(treeUri: String): Boolean            // takePersistableUriPermission
    suspend fun listRecordings(): List<StoredDocument>
    suspend fun freeBytes(): Long
    suspend fun createDocument(name: String, mime: String): ByteSink
    suspend fun readText(name: String): String?
    suspend fun writeText(name: String, content: String)
    suspend fun existingNames(): Set<String>
}

// ---------------------------------------------------------------------------
// Playback (Requirement 7)
// ---------------------------------------------------------------------------

/** Outcome of starting playback of a recording. */
data class PlaybackResult(
    val success: Boolean,
    val durationMillis: Long = 0L,
    val errorMessage: String? = null
)

interface MediaPlaybackController {
    suspend fun play(name: String): PlaybackResult
    fun pause()
    fun resume()
    fun stop()
    val positionMillis: Long
}

// ---------------------------------------------------------------------------
// Cross-cutting
// ---------------------------------------------------------------------------

interface Clock {
    fun nowMillis(): Long
}

interface PermissionChecker {
    fun status(p: RuntimePermission): Boolean
    fun shouldOpenSettings(p: RuntimePermission): Boolean
}

interface StatusLog {
    fun record(entry: StatusEntry)
}
