package net.alexsobolev.tts.domain

/**
 * Global constants for the Kokoro TTS pipeline: model limits, defaults, and storage settings.
 */
object Constants {
    /**
     * Audio sample rate produced by the Kokoro model.
     */
    const val SAMPLE_RATE = 24_000

    /**
     * Dimension of the voice style embedding vector.
     */
    const val STYLE_VECTOR_DIM = 256

    /**
     * Padding (BOS/EOS) token ID expected by the model.
     */
    const val PAD_TOKEN = 0L

    /**
     * Maximum real phoneme tokens per inference call (512 context minus 2 padding).
     */
    const val MAX_PHONEME_TOKENS = 510

    /**
     * Minimum allowed speech rate multiplier.
     */
    const val MIN_SPEED = 0.5

    /**
     * Maximum allowed speech rate multiplier.
     */
    const val MAX_SPEED = 2.0

    /**
     * Default speed when the caller does not specify one.
     */
    const val DEFAULT_SPEED = 1.0

    /**
     * Default voice ID when the caller does not specify one.
     */
    const val DEFAULT_VOICE_ID = "af_heart"

    /**
     * Default output audio format.
     */
    const val DEFAULT_FORMAT = "wav"

    /**
     * Maximum input text length in characters.
     */
    const val MAX_TEXT_LENGTH = 5_000

    /**
     * Minimum silence between dialogue turns in milliseconds.
     */
    const val MIN_TURN_GAP_MS = 250

    /**
     * Maximum silence between dialogue turns in milliseconds.
     */
    const val MAX_TURN_GAP_MS = 500
}
