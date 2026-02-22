package net.alexsobolev.tts.domain

/**
 * Supported output audio formats with their encoding parameters.
 */
sealed interface AudioFormat {
    val sampleRate: SampleRate

    /**
     * Uncompressed PCM waveform format.
     */
    data class WAV(override val sampleRate: SampleRate, val bitDepth: Int = 16) : AudioFormat

    /**
     * MPEG Layer-3 compressed format.
     */
    data class MP3(override val sampleRate: SampleRate, val bitRate: Int = 128) : AudioFormat
}
