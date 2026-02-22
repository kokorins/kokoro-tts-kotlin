package net.alexsobolev.tts.domain

/**
 * Unique identifier for a Kokoro voice (e.g. "af_heart", "bf_emma").
 *
 * @param value the non-blank voice identifier string
 */
@JvmInline value class VoiceId(val value: String) {
    init {
        require(value.isNotBlank()) { "VoiceId cannot be blank" }
    }
}
