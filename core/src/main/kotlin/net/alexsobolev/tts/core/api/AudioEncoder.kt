package net.alexsobolev.tts.core.api

import net.alexsobolev.tts.domain.AudioChunk
import net.alexsobolev.tts.domain.AudioFormat

/**
 * Encodes raw float32 audio samples into a specific output format (WAV or MP3).
 */
interface AudioEncoder {
    /**
     * Encode raw audio samples into the specified format.
     *
     * @param samples raw float32 audio samples in [-1.0, 1.0]
     * @param format target audio format (WAV or MP3)
     * @return encoded audio chunk containing the byte data and format
     */
    suspend fun encode(samples: FloatArray, format: AudioFormat): AudioChunk
}
