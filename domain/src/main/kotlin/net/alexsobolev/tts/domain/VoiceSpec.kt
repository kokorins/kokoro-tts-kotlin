package net.alexsobolev.tts.domain

/**
 * Specifies which voice(s) to use for synthesis: a single voice or a weighted blend.
 */
sealed interface VoiceSpec {
    /**
     * Use a single voice identified by [voiceId].
     */
    data class SingleVoice(val voiceId: VoiceId) : VoiceSpec

    /**
     * Blend multiple voices by weighted averaging of their embedding vectors.
     */
    data class BlendedVoice(val voices: List<Pair<VoiceId, VoiceBlendWeight>>) : VoiceSpec {
        init {
            require(voices.size in 2..MAX_BLEND_VOICES) {
                "Blend requires 2–$MAX_BLEND_VOICES voices, got ${voices.size}"
            }
            val totalWeight = voices.sumOf { it.second.weight.toDouble() }
            require(totalWeight in 0.99..1.01) {
                "Blend weights must sum to 1.0, was $totalWeight"
            }
        }
    }
}

private const val MAX_BLEND_VOICES = 4
