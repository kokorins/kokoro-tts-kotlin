package net.alexsobolev.tts.domain

/**
 * An IPA phoneme string produced by the grapheme-to-phoneme pipeline.
 *
 * @param value the non-blank IPA phoneme string
 */
data class PhonemeSequence(val value: String) {
    val length: Int get() = value.length

    init {
        require(value.isNotBlank()) { "Phoneme sequence cannot be blank" }
    }

    override fun toString(): String = "PhonemeSequence(length=$length)"
}
