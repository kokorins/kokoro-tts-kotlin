package net.alexsobolev.tts.infra.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Converts IPA phoneme strings to Kokoro token IDs.
 *
 * The vocabulary is loaded from the model's config.json file
 * (https://huggingface.co/hexgrad/Kokoro-82M/blob/main/config.json).
 * The config contains a "vocab" object where each key is a single
 * phoneme character and each value is the corresponding integer
 * token ID.
 *
 * Characters not present in the vocabulary are silently
 * skipped, matching the behavior of the reference
 * implementation.
 */
internal class KokoroTokenizer(configPath: String) {
    private val vocab: Map<Char, Int>

    init {
        val json = Json { ignoreUnknownKeys = true }
        val config = json.decodeFromString<KokoroConfig>(File(configPath).readText())
        vocab = config.vocab.mapKeys { (key, _) -> key.single() }
    }

    /**
     * Convert a phoneme string to token IDs.
     *
     * Each character in the input is looked up in the vocabulary.
     * Unknown characters are skipped. The result does NOT include
     * BOS/EOS padding. The caller (OnnxKokoroEngine) wraps the
     * result with padding zeros before feeding it to the model.
     *
     * @param phonemes IPA phoneme string to tokenize
     * @return array of token IDs corresponding to known phoneme characters
     */
    fun tokenize(phonemes: String): LongArray {
        val buf = LongArray(phonemes.length)
        var count = 0
        for (ch in phonemes) {
            val id = vocab[ch]
            if (id != null) {
                buf[count++] = id.toLong()
            }
        }
        return if (count == buf.size) buf else buf.copyOf(count)
    }
}

/**
 * Deserialization target for the Kokoro model's config.json vocabulary mapping.
 */
@Serializable
private data class KokoroConfig(val vocab: Map<String, Int>)
