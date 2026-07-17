package com.personal.unifiedrecorder.adapter

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.personal.unifiedrecorder.core.model.CaptureConfig
import com.personal.unifiedrecorder.core.port.AudioCaptureDevice
import com.personal.unifiedrecorder.core.port.ByteSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [AudioCaptureDevice] backed by [AudioRecord] using [MediaRecorder.AudioSource.VOICE_COMMUNICATION]
 * and 16-bit PCM. This is the path used whenever the DSP pipeline or Smart Silence optimization is
 * active, because those stages require raw PCM frames (Requirements 2.1, 2.2, 4.2, 5.1, 6.3).
 *
 * Frames are read on [Dispatchers.IO] and emitted as [ShortArray] batches, keeping the capture loop
 * off the main thread.
 *
 * Device-only / manual verification: real PCM capture requires microphone hardware, the RECORD_AUDIO
 * grant, and an active call; it cannot be exercised on the JVM or an emulator.
 */
class AudioRecordCaptureDevice : AudioCaptureDevice {

    private val capturing = AtomicBoolean(false)

    @Volatile
    private var record: AudioRecord? = null

    override fun pcmFrames(config: CaptureConfig): Flow<ShortArray> = flow {
        val minBytes = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBytes > 0) { "Unsupported AudioRecord configuration for ${config.sampleRate} Hz" }

        val bufferBytes = minBytes * BUFFER_MULTIPLIER
        val audioRecord = newAudioRecord(config.sampleRate, bufferBytes)
        record = audioRecord
        capturing.set(true)

        val frame = ShortArray(bufferBytes / 2)
        try {
            audioRecord.startRecording()
            while (capturing.get()) {
                val read = audioRecord.read(frame, 0, frame.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) break
                    continue
                }
                emit(frame.copyOf(read))
            }
        } finally {
            releaseRecord(audioRecord)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Raw-PCM encoded path: streams little-endian 16-bit samples into [sink] until [stop] is called.
     * Suitable for the rooted stealth path where the captured bytes are preserved unmodified.
     * Returns the total number of bytes written.
     */
    @SuppressLint("MissingPermission")
    override suspend fun captureEncoded(config: CaptureConfig, sink: ByteSink): Long {
        val minBytes = AudioRecord.getMinBufferSize(
            config.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBytes > 0) { "Unsupported AudioRecord configuration for ${config.sampleRate} Hz" }

        val bufferBytes = minBytes * BUFFER_MULTIPLIER
        val audioRecord = newAudioRecord(config.sampleRate, bufferBytes)
        record = audioRecord
        capturing.set(true)

        val shorts = ShortArray(bufferBytes / 2)
        val bytes = ByteArray(bufferBytes)
        var total = 0L
        try {
            audioRecord.startRecording()
            while (capturing.get()) {
                val read = audioRecord.read(shorts, 0, shorts.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) break
                    continue
                }
                val byteCount = read * 2
                for (i in 0 until read) {
                    val s = shorts[i].toInt()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                sink.write(bytes, 0, byteCount)
                total += byteCount
            }
            sink.close()
        } catch (t: Throwable) {
            sink.abort()
            throw t
        } finally {
            releaseRecord(audioRecord)
        }
        return total
    }

    override suspend fun stop() {
        capturing.set(false)
    }

    @SuppressLint("MissingPermission")
    private fun newAudioRecord(sampleRate: Int, bufferBytes: Int): AudioRecord =
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )

    private fun releaseRecord(audioRecord: AudioRecord) {
        capturing.set(false)
        runCatching { if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop() }
        runCatching { audioRecord.release() }
        if (record === audioRecord) record = null
    }

    private companion object {
        const val BUFFER_MULTIPLIER = 2
    }
}
