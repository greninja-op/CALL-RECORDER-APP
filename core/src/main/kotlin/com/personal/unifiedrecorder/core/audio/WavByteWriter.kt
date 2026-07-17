package com.personal.unifiedrecorder.core.audio

import java.io.ByteArrayOutputStream

/**
 * Deterministically produces a canonical 44-byte WAV header (PCM, 16-bit) plus a
 * little-endian PCM payload for a fixed sample rate and channel count.
 *
 * The writer supports *incremental* writing: callers append only the frames they
 * choose to serialize (for example, when Smart Silence pauses/resumes), and the
 * emitted file always stays structurally valid — the data-chunk length equals the
 * number of PCM bytes actually written and is a whole multiple of the frame size.
 *
 * A "frame" is one 16-bit sample per channel, so `frameSize = channels * 2`. The
 * reported [durationSeconds] equals `totalFrames / sampleRate` and the header's
 * data-chunk length equals the PCM payload byte count.
 *
 * _Requirements: 2.2, 6.3_
 */
class WavByteWriter(
    val sampleRate: Int,
    val channels: Int = 1
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(channels >= 1) { "channels must be >= 1, was $channels" }
    }

    /** Bytes per single-channel sample for 16-bit PCM. */
    val bytesPerSample: Int = BITS_PER_SAMPLE / 8

    /** Bytes for one full frame (all channels). */
    val frameSize: Int = channels * bytesPerSample

    private val payload = ByteArrayOutputStream()

    /**
     * Appends [samples] (interleaved by channel) to the PCM payload as
     * little-endian 16-bit values. Only whole samples are written, so the payload
     * length always remains a multiple of [bytesPerSample]; when [samples] holds a
     * whole number of frames the payload stays a multiple of [frameSize].
     */
    fun writeFrames(samples: ShortArray) {
        for (s in samples) {
            val v = s.toInt()
            payload.write(v and 0xFF)
            payload.write((v shr 8) and 0xFF)
        }
    }

    /** Number of PCM payload bytes written so far. */
    val dataLength: Int get() = payload.size()

    /** Number of complete frames written so far. */
    val totalFrames: Int get() = dataLength / frameSize

    /** Reported duration in seconds = totalFrames / sampleRate. */
    val durationSeconds: Double get() = totalFrames.toDouble() / sampleRate

    /** Builds the canonical 44-byte PCM WAV header reflecting the current payload. */
    fun header(): ByteArray {
        val dataLen = dataLength
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample
        val header = ByteArray(HEADER_SIZE)
        // RIFF chunk descriptor
        putAscii(header, 0, "RIFF")
        putIntLe(header, 4, 36 + dataLen)          // ChunkSize = 36 + Subchunk2Size
        putAscii(header, 8, "WAVE")
        // fmt sub-chunk
        putAscii(header, 12, "fmt ")
        putIntLe(header, 16, 16)                   // Subchunk1Size (PCM)
        putShortLe(header, 20, 1)                  // AudioFormat = 1 (PCM)
        putShortLe(header, 22, channels)
        putIntLe(header, 24, sampleRate)
        putIntLe(header, 28, byteRate)
        putShortLe(header, 32, blockAlign)
        putShortLe(header, 34, BITS_PER_SAMPLE)
        // data sub-chunk
        putAscii(header, 36, "data")
        putIntLe(header, 40, dataLen)              // Subchunk2Size = PCM payload byte count
        return header
    }

    /** Full WAV file bytes: header followed by the PCM payload written so far. */
    fun toByteArray(): ByteArray = header() + payload.toByteArray()

    companion object {
        const val BITS_PER_SAMPLE: Int = 16
        const val HEADER_SIZE: Int = 44

        /** Reads the little-endian data-chunk length field from a canonical header. */
        fun readDataChunkLength(bytes: ByteArray): Int {
            require(bytes.size >= HEADER_SIZE) { "buffer too small to contain a WAV header" }
            return (bytes[40].toInt() and 0xFF) or
                ((bytes[41].toInt() and 0xFF) shl 8) or
                ((bytes[42].toInt() and 0xFF) shl 16) or
                ((bytes[43].toInt() and 0xFF) shl 24)
        }

        private fun putAscii(dst: ByteArray, offset: Int, s: String) {
            for (i in s.indices) dst[offset + i] = s[i].code.toByte()
        }

        private fun putIntLe(dst: ByteArray, offset: Int, value: Int) {
            dst[offset] = (value and 0xFF).toByte()
            dst[offset + 1] = ((value shr 8) and 0xFF).toByte()
            dst[offset + 2] = ((value shr 16) and 0xFF).toByte()
            dst[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        private fun putShortLe(dst: ByteArray, offset: Int, value: Int) {
            dst[offset] = (value and 0xFF).toByte()
            dst[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
    }
}
