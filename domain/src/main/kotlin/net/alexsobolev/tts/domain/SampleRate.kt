package net.alexsobolev.tts.domain

/**
 * Audio sample rate in hertz, restricted to standard rates (8 kHz-48 kHz).
 *
 * @param hz the sample rate in hertz (must be one of 8000, 16000, 22050, 24000, 44100, 48000)
 */
@JvmInline
value class SampleRate(val hz: Int) {
    init {
        require(hz in setOf(8000, 16000, 22050, 24000, 44100, 48000))
    }
}
