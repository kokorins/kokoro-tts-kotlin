package net.alexsobolev.tts.domain

/**
 * Typed error hierarchy for failures that can occur during TTS synthesis.
 */
sealed class SynthesisException(override val message: String) : Exception(message) {
    /**
     * The requested voice ID does not exist in the voice repository.
     */
    data class VoiceNotFound(val voiceId: String) : SynthesisException("Voice '$voiceId' not found")

    /**
     * A single turn's text exceeds the per-turn character limit.
     */
    data class TextTooLong(val length: Int, val max: Int) :
        SynthesisException(
            "Text length $length exceeds maximum $max",
        )

    /**
     * The requested speed multiplier is outside the allowed range.
     */
    data class SpeedOutOfRange(val speed: Double) :
        SynthesisException(
            "Speed $speed is out of range (${Constants.MIN_SPEED} to ${Constants.MAX_SPEED})",
        )

    /**
     * The ONNX inference engine failed to produce audio samples.
     */
    data class InferenceFailed(val reason: String) : SynthesisException("Synthesis failed: $reason")

    /**
     * Uploading the encoded audio to remote storage failed.
     */
    data class StorageFailed(val reason: String) : SynthesisException("Audio storage failed: $reason")

    /**
     * The caller submitted a dialogue request with zero turns.
     */
    class DialogueEmpty :
        SynthesisException(
            "Dialogue must have at least one turn",
        )

    /**
     * The input request contained malformed data (e.g. invalid voice blend weights).
     */
    data class InvalidInput(val reason: String) : SynthesisException("Invalid input: $reason")
}
