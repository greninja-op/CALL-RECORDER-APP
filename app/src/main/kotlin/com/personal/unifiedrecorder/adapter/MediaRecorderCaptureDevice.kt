package com.personal.unifiedrecorder.adapter

import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import com.personal.unifiedrecorder.core.model.AudioFormat
import com.personal.unifiedrecorder.core.model.CaptureConfig
import com.personal.unifiedrecorder.core.port.AudioCaptureDevice
import com.personal.unifiedrecorder.core.port.ByteSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [AudioCaptureDevice] backed by [MediaRecorder] for encoded capture on the plain unrooted path
 * (no DSP / Smart Silence) and the rooted path (Requirements 3.x, 6.3).
 *
 * [MediaRecorder] emits an encoded container, not raw PCM, so [pcmFrames] is unsupported here; the
 * DSP / Smart Silence path must use [AudioRecordCaptureDevice] instead. [captureEncoded] wires the
 * recorder's output through an in-process pipe so the encoded bytes are streamed into a [ByteSink]
 * (e.g. a SAF document sink) rather than a private file.
 *
 * Device-only / manual verification: real encoding requires microphone hardware and an active call.
 */
class MediaRecorderCaptureDevice : AudioCaptureDevice {

    private val capturing = AtomicBoolean(false)

    @Volatile
    private var recorder: MediaRecorder? = null

    override fun pcmFrames(config: CaptureConfig): Flow<ShortArray> =
        throw UnsupportedOperationException(
            "MediaRecorder does not expose raw PCM frames; use AudioRecordCaptureDevice for the DSP/Smart-Silence path."
        )

    override suspend fun captureEncoded(config: CaptureConfig, sink: ByteSink): Long =
        withContext(Dispatchers.IO) {
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]

            val mediaRecorder = newMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(outputFormatFor(config.format))
                setAudioEncoder(audioEncoderFor(config.format))
                setAudioSamplingRate(config.sampleRate)
                setOutputFile(writeSide.fileDescriptor)
                prepare()
            }
            recorder = mediaRecorder
            capturing.set(true)

            var total = 0L
            try {
                mediaRecorder.start()
                // The recorder writes to the pipe's write side; drain the read side into the sink.
                FileInputStream(readSide.fileDescriptor).use { input ->
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (capturing.get()) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            sink.write(buffer, 0, read)
                            total += read
                        }
                    }
                }
                sink.close()
            } catch (t: Throwable) {
                sink.abort()
                throw t
            } finally {
                releaseRecorder(mediaRecorder)
                runCatching { writeSide.close() }
                runCatching { readSide.close() }
            }
            total
        }

    override suspend fun stop() {
        capturing.set(false)
    }

    @Suppress("DEPRECATION")
    private fun newMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContextOrThrow())
        } else {
            MediaRecorder()
        }

    /**
     * [MediaRecorder] on API 31+ requires a [android.content.Context]. It is not otherwise needed,
     * so this device is expected to be constructed with one supplied by the app graph in a later
     * wiring task; until then the no-arg constructor path (API <= 30) is used.
     */
    private fun appContextOrThrow(): android.content.Context =
        context ?: throw IllegalStateException(
            "MediaRecorderCaptureDevice requires a Context on API 31+; supply one via [context]."
        )

    /** Optional context for API 31+ [MediaRecorder]; injected by the app graph during wiring. */
    @Volatile
    var context: android.content.Context? = null

    private fun outputFormatFor(format: AudioFormat): Int = when (format) {
        AudioFormat.AAC, AudioFormat.MP4 -> MediaRecorder.OutputFormat.MPEG_4
        AudioFormat.WAV -> MediaRecorder.OutputFormat.MPEG_4 // WAV is handled by the PCM path; fall back to MPEG_4 here.
    }

    private fun audioEncoderFor(format: AudioFormat): Int = when (format) {
        AudioFormat.AAC, AudioFormat.MP4, AudioFormat.WAV -> MediaRecorder.AudioEncoder.AAC
    }

    private fun releaseRecorder(mediaRecorder: MediaRecorder) {
        capturing.set(false)
        runCatching { mediaRecorder.stop() }
        runCatching { mediaRecorder.reset() }
        runCatching { mediaRecorder.release() }
        if (recorder === mediaRecorder) recorder = null
    }

    private companion object {
        const val READ_BUFFER_BYTES = 8 * 1024
    }
}
