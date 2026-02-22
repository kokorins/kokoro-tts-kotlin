package net.alexsobolev.tts.core.api

import net.alexsobolev.tts.domain.PhonemeSequence

/**
 * Converts natural-language text into an IPA phoneme sequence for the inference engine.
 */
interface PhonemeGenerator {
    /**
     * Generate an IPA phoneme sequence from natural-language text.
     *
     * @param text the input text to phonemize
     * @return phoneme sequence suitable for the inference engine
     */
    suspend fun generate(text: String): PhonemeSequence
}
