package net.alexsobolev.tts.domain

/**
 * Speech speed multiplier, clamped to [Constants.MIN_SPEED]..[Constants.MAX_SPEED].
 *
 * @param value the speed multiplier within the allowed range
 */
@JvmInline value class SpeechRate(val value: Double) {
    init {
        require(value in Constants.MIN_SPEED..Constants.MAX_SPEED) {
            "Speed must be between ${Constants.MIN_SPEED} and ${Constants.MAX_SPEED}, was $value"
        }
    }
}
