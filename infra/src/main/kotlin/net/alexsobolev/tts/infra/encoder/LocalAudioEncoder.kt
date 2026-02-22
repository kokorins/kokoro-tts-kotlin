package net.alexsobolev.tts.infra.encoder

import de.sciss.jump3r.lowlevel.LameEncoder
import net.alexsobolev.tts.core.api.AudioEncoder
import net.alexsobolev.tts.core.utils.logger
import net.alexsobolev.tts.domain.AudioChunk
import net.alexsobolev.tts.domain.AudioFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-process audio encoder that converts float32 samples to WAV (PCM16) or MP3 (via jump3r).
 */
internal class LocalAudioEncoder : AudioEncoder {
    private val logger = logger()

    override suspend fun encode(samples: FloatArray, format: AudioFormat): AudioChunk {
        logger.debug(
            "Encoding {} samples as {}",
            samples.size,
            format::class.simpleName,
        )
        val startMs = System.currentTimeMillis()

        val encoded =
            when (format) {
                is AudioFormat.WAV ->
                    encodeToWav(
                        samples,
                        format.sampleRate.hz,
                    )
                is AudioFormat.MP3 ->
                    encodeToMp3(
                        samples,
                        format.sampleRate.hz,
                        format.bitRate,
                    )
            }

        val elapsed = System.currentTimeMillis() - startMs
        logger.info(
            "Encoded {} bytes in {}ms ({})",
            encoded.size,
            elapsed,
            format::class.simpleName,
        )

        return AudioChunk(data = encoded, format = format)
    }

    /**
     * Encodes float32 samples into a WAV byte array with a 44-byte RIFF header.
     *
     * @param samples raw float32 audio samples in [-1.0, 1.0]
     * @param sampleRate sample rate in hertz
     * @return complete WAV file as a byte array
     */
    private fun encodeToWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcmBytes = floatToPcm16(samples)
        val header =
            createWavHeader(
                sampleRate = sampleRate,
                dataSize = pcmBytes.size,
            )
        return header + pcmBytes
    }

    /**
     * Build a 44-byte WAV file header.
     *
     * Format: RIFF/WAVE, PCM, mono, 16-bit, little-endian.
     * The header size is always 44 bytes regardless of data size.
     *
     * @param sampleRate audio sample rate in hertz
     * @param dataSize PCM data size in bytes
     * @return 44-byte WAV header as a byte array
     */
    private fun createWavHeader(sampleRate: Int, dataSize: Int): ByteArray {
        val byteRate = sampleRate * 2 // mono, 16 bit
        val fileSize = 36 + dataSize

        val buffer =
            ByteBuffer
                .allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray())
        buffer.putInt(fileSize)
        buffer.put("WAVE".toByteArray())

        // fmt chunk
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // chunk size
        buffer.putShort(1) // PCM format
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate) // sample rate
        buffer.putInt(byteRate) // byte rate
        buffer.putShort(2) // block align
        buffer.putShort(16) // bits per sample

        // data chunk
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)

        return buffer.array()
    }

    /**
     * Encodes float32 samples into an MP3 byte array using the jump3r LAME encoder.
     *
     * @param samples raw float32 audio samples in [-1.0, 1.0]
     * @param sampleRate sample rate in hertz
     * @param bitRate target MP3 bit rate in kbps
     * @return MP3 file as a byte array
     */
    private fun encodeToMp3(samples: FloatArray, sampleRate: Int, bitRate: Int): ByteArray {
        val pcmBytes = floatToPcm16(samples)
        val encoder =
            setupLameEncoder(
                sampleRate = sampleRate,
                channels = 1,
                bitRate = bitRate,
            )

        val output = ByteArrayOutputStream()
        val mp3Buffer = ByteArray(encoder.pcmBufferSize)

        var offset = 0
        while (offset < pcmBytes.size) {
            val chunkSize =
                minOf(
                    encoder.pcmBufferSize,
                    pcmBytes.size - offset,
                )
            val chunk = pcmBytes.copyOfRange(offset, offset + chunkSize)
            val bytesEncoded =
                encoder.encodeBuffer(
                    chunk,
                    0,
                    chunkSize,
                    mp3Buffer,
                )
            if (bytesEncoded > 0) {
                output.write(mp3Buffer, 0, bytesEncoded)
            }
            offset += chunkSize
        }

        val flushed = encoder.encodeFinish(mp3Buffer)
        if (flushed > 0) {
            output.write(mp3Buffer, 0, flushed)
        }
        encoder.close()

        return output.toByteArray()
    }

    /**
     * Configure a jump3r LAME encoder for the given parameters.
     *
     * jump3r is a pure Java port of the LAME MP3 encoder.
     * It produces standard MP3 frames compatible with all players.
     *
     * @param sampleRate audio sample rate in hertz
     * @param channels number of audio channels (1 = mono, 2 = stereo)
     * @param bitRate target bit rate in kbps
     * @return configured [LameEncoder] ready for encoding
     */
    private fun setupLameEncoder(sampleRate: Int, channels: Int, bitRate: Int): LameEncoder {
        return LameEncoder(
            javax.sound.sampled.AudioFormat(
                sampleRate.toFloat(),
                16,
                channels,
                // signed
                true,
                // little endian
                false,
            ),
            bitRate,
            LameEncoder.CHANNEL_MODE_MONO,
            LameEncoder.QUALITY_HIGH,
            // VBR off
            false,
        )
    }

    /**
     * Convert float32 audio samples to 16-bit PCM bytes.
     *
     * Each float in [-1.0, 1.0] maps to a signed 16-bit integer
     * in [-32767, 32767], stored as two little-endian bytes.
     * Values outside [-1.0, 1.0] are clamped before conversion.
     *
     * @param samples raw float32 audio samples
     * @return PCM16 byte array (2 bytes per sample, little-endian)
     */
    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            val pcmVal = (clamped * 32767).toInt().toShort()
            bytes[i * 2] = (pcmVal.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (pcmVal.toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }
}
