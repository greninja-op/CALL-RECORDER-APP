package com.personal.unifiedrecorder.core.fake

import com.personal.unifiedrecorder.core.model.CaptureConfig
import com.personal.unifiedrecorder.core.model.StatusEntry
import com.personal.unifiedrecorder.core.port.AudioCaptureDevice
import com.personal.unifiedrecorder.core.port.AudioRoutingController
import com.personal.unifiedrecorder.core.port.ByteSink
import com.personal.unifiedrecorder.core.port.Clock
import com.personal.unifiedrecorder.core.port.DocumentStore
import com.personal.unifiedrecorder.core.port.MediaPlaybackController
import com.personal.unifiedrecorder.core.port.PlaybackResult
import com.personal.unifiedrecorder.core.port.RoutingResult
import com.personal.unifiedrecorder.core.port.StatusLog
import com.personal.unifiedrecorder.core.port.StoredDocument
import com.personal.unifiedrecorder.core.port.SuperuserProbe
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * In-memory JVM fakes for the ports, used by the orchestration example and property tests.
 *
 * These are deliberately deterministic and configurable so orchestration behaviors can be
 * exercised without any Android framework, real audio, real storage, or real clock.
 */

/**
 * A [Clock] backed by a mutable counter. It can act as a fixed clock (default) or as a
 * monotonic clock that advances a fixed amount on every read via [autoAdvanceMillis].
 */
class FakeClock(
    start: Long = 0L,
    private val autoAdvanceMillis: Long = 0L
) : Clock {
    var current: Long = start

    override fun nowMillis(): Long {
        val now = current
        current += autoAdvanceMillis
        return now
    }

    /** Advance the clock by [millis]. */
    fun advance(millis: Long) {
        current += millis
    }

    /** Set the clock to an absolute value. */
    fun set(millis: Long) {
        current = millis
    }
}

/** An append-only [StatusLog] that exposes its recorded entries for assertions. */
class FakeStatusLog : StatusLog {
    private val backing = mutableListOf<StatusEntry>()

    /** A snapshot copy of the recorded entries, in insertion order. */
    val entries: List<StatusEntry> get() = backing.toList()

    override fun record(entry: StatusEntry) {
        backing.add(entry)
    }

    /** Count the recorded entries of a given concrete [StatusEntry] type. */
    inline fun <reified T : StatusEntry> countOf(): Int = entries.count { it is T }
}

/** A [SuperuserProbe] whose availability is configurable. */
class FakeSuperuserProbe(var available: Boolean = false) : SuperuserProbe {
    override suspend fun isSuperuserAvailable(): Boolean = available
}

/** An [AudioRoutingController] with a configurable [apiLevel] and success/failure result. */
class FakeAudioRoutingController(
    override val apiLevel: Int = 31,
    var succeed: Boolean = true,
    var message: String? = null
) : AudioRoutingController {
    var routeCalls: Int = 0
        private set
    var restoreCalls: Int = 0
        private set

    override suspend fun routeToLoudspeaker(): RoutingResult {
        routeCalls++
        return RoutingResult(success = succeed, message = message)
    }

    override suspend fun restorePreviousRouting() {
        restoreCalls++
    }
}

/** A [MediaPlaybackController] returning a configurable [result]. */
class FakeMediaPlaybackController(
    var result: PlaybackResult = PlaybackResult(success = true)
) : MediaPlaybackController {
    override val positionMillis: Long = 0L

    override suspend fun play(name: String): PlaybackResult = result
    override fun pause() {}
    override fun resume() {}
    override fun stop() {}
}

/**
 * An [AudioCaptureDevice] that emits a scripted sequence of PCM [frames] (optionally throwing
 * after [errorAfterFrames] frames), and a configurable encoded-capture outcome.
 */
class FakeAudioCaptureDevice(
    private val frames: List<ShortArray> = emptyList(),
    private val errorAfterFrames: Int? = null,
    private val encodedBytes: ByteArray = ByteArray(0),
    private val failEncoded: Boolean = false
) : AudioCaptureDevice {
    var stopped: Boolean = false
        private set

    override fun pcmFrames(config: CaptureConfig): Flow<ShortArray> = flow {
        frames.forEachIndexed { index, frame ->
            if (errorAfterFrames != null && index >= errorAfterFrames) {
                throw IOException("scripted capture error at frame $index")
            }
            emit(frame)
        }
    }

    override suspend fun captureEncoded(config: CaptureConfig, sink: ByteSink): Long {
        if (failEncoded) throw IOException("scripted encoded-capture failure")
        sink.write(encodedBytes, 0, encodedBytes.size)
        sink.close()
        return encodedBytes.size.toLong()
    }

    override suspend fun stop() {
        stopped = true
    }

    companion object {
        /** A single silent PCM frame of [size] zero samples. */
        fun silentFrame(size: Int = 256): ShortArray = ShortArray(size)

        /** A single audible PCM frame of [size] samples at [amplitude]. */
        fun audibleFrame(size: Int = 256, amplitude: Short = 8_000): ShortArray =
            ShortArray(size) { amplitude }

        /** A capture device scripted with [count] silent frames. */
        fun silent(count: Int = 8, frameSize: Int = 256): FakeAudioCaptureDevice =
            FakeAudioCaptureDevice(frames = List(count) { silentFrame(frameSize) })

        /** A capture device scripted with [count] audible frames. */
        fun audible(count: Int = 8, frameSize: Int = 256, amplitude: Short = 8_000): FakeAudioCaptureDevice =
            FakeAudioCaptureDevice(frames = List(count) { audibleFrame(frameSize, amplitude) })
    }
}

/**
 * An in-memory [DocumentStore] confined to a single bound tree.
 *
 * - Audio recordings live in [files]; JSON companions live in [texts]. Both are enumerated by
 *   [listRecordings] and [existingNames] and only these are ever surfaced.
 * - [externalFiles] models content residing outside the bound tree; it is never listed, read, or
 *   otherwise surfaced (source-isolation, Req 6.8).
 * - Failures are configurable: [failCreate], [failWrite], [failClose], [failWriteText],
 *   [failReadText]. The produced [ByteSink] retains any written portion in [files] when a write or
 *   close fails, so failures are non-destructive (Req 6.6).
 */
class FakeDocumentStore(
    private var bound: Boolean = true,
    var freeBytes: Long = Long.MAX_VALUE,
    var failCreate: Boolean = false,
    var failWrite: Boolean = false,
    var failClose: Boolean = false,
    var failWriteText: Boolean = false,
    var failReadText: Boolean = false
) : DocumentStore {

    /** Audio recordings under the bound tree (name -> bytes). */
    val files: LinkedHashMap<String, ByteArray> = LinkedHashMap()

    /** JSON companions under the bound tree (name -> content). */
    val texts: LinkedHashMap<String, String> = LinkedHashMap()

    /** Content residing outside the bound tree; must never be surfaced. */
    val externalFiles: LinkedHashMap<String, ByteArray> = LinkedHashMap()

    override suspend fun isBound(): Boolean = bound

    override suspend fun bind(treeUri: String): Boolean {
        bound = true
        return true
    }

    override suspend fun listRecordings(): List<StoredDocument> {
        val audio = files.map { (name, bytes) ->
            StoredDocument(name = name, mimeType = mimeFor(name), sizeBytes = bytes.size.toLong(), lastModifiedMillis = 0L)
        }
        val companions = texts.map { (name, content) ->
            StoredDocument(name = name, mimeType = "application/json", sizeBytes = content.length.toLong(), lastModifiedMillis = 0L)
        }
        return audio + companions
    }

    override suspend fun freeBytes(): Long = freeBytes

    override suspend fun createDocument(name: String, mime: String): ByteSink {
        if (failCreate) throw IOException("scripted createDocument failure for $name")
        return FakeByteSink(name)
    }

    override suspend fun readText(name: String): String? {
        if (failReadText) throw IOException("scripted readText failure for $name")
        return texts[name]
    }

    override suspend fun writeText(name: String, content: String) {
        if (failWriteText) throw IOException("scripted writeText failure for $name")
        texts[name] = content
    }

    override suspend fun existingNames(): Set<String> = files.keys + texts.keys

    /** Seed an audio recording as if already present in the bound tree. */
    fun seedRecording(name: String, bytes: ByteArray = ByteArray(0)) {
        files[name] = bytes
    }

    /** Seed a companion JSON as if already present in the bound tree. */
    fun seedCompanion(name: String, content: String) {
        texts[name] = content
    }

    /** Seed content outside the bound tree; it must never be surfaced. */
    fun seedExternal(name: String, bytes: ByteArray = ByteArray(0)) {
        externalFiles[name] = bytes
    }

    private fun mimeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "wav" -> "audio/wav"
            "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }

    /** A [ByteSink] that writes into [files], simulating mid-write / on-close failures. */
    inner class FakeByteSink(private val name: String) : ByteSink {
        private val buffer = ByteArrayOutputStream()

        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (failWrite) {
                // Retain the partial (half) portion actually "written" before failing.
                val partial = length / 2
                buffer.write(bytes, offset, partial)
                files[name] = buffer.toByteArray()
                throw IOException("scripted write failure for $name")
            }
            buffer.write(bytes, offset, length)
        }

        override suspend fun close() {
            files[name] = buffer.toByteArray()
            if (failClose) throw IOException("scripted close failure for $name")
        }

        override suspend fun abort() {
            files.remove(name)
        }
    }
}
