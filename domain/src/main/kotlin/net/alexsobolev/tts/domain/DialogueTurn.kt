package net.alexsobolev.tts.domain

/**
 * A single turn in a multi-voice dialogue, pairing a voice with the text it speaks.
 *
 * @param voice the voice specification to use for this turn
 * @param text the text content to synthesize (must not be blank)
 */
data class DialogueTurn(val voice: VoiceSpec, val text: String) {
    init {
        require(text.isNotBlank()) { "Dialogue turn text cannot be blank" }
    }
}
