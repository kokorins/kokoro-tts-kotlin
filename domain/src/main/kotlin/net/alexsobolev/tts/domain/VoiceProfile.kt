package net.alexsobolev.tts.domain

/**
 * A loaded voice with its embedding vector, used by the inference engine to generate speech.
 *
 * @param id the unique identifier for this voice
 * @param name the human-readable display name of the voice
 * @param embedding the style embedding vector used by the model
 * @param language the BCP-47 language code for this voice
 */
class VoiceProfile(
    val id: VoiceId,
    val name: String,
    val embedding: FloatArray,
    val language: String,
) {
    override fun equals(other: Any?) = other is VoiceProfile && id == other.id

    override fun hashCode() = id.hashCode()
}
