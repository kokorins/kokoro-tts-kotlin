package net.alexsobolev.tts.domain

/**
 * Normalized weight for a single voice in a blended voice spec.
 *
 * @param weight the blend weight, must be in the range (0.0, 1.0] (exclusive zero)
 */
@JvmInline value class VoiceBlendWeight(val weight: Float) {
    init {
        require(weight > 0.0f && weight <= 1.0f) {
            "Weight must be between 0.0 (exclusive) and 1.0 (inclusive)"
        }
    }
}
