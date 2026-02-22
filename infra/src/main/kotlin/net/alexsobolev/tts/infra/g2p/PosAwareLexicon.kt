package net.alexsobolev.tts.infra.g2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import net.alexsobolev.tts.core.utils.logger
import java.io.File

/**
 * POS-aware pronunciation lexicon that preserves per-POS variants from Misaki dictionaries.
 *
 * Entries like `"live": {"DEFAULT": "lˈIv", "VERB": "lˈɪv"}` are stored as nested maps.
 * [lookup] selects the right variant based on the token's POS tag and surrounding context.
 */
internal class PosAwareLexicon(
    goldDictionaryPath: String,
    silverDictionaryPath: String,
    gbGoldDictionaryPath: String,
    gbSilverDictionaryPath: String,
    fixesDictionaryPath: String? = null,
) {
    private val logger = logger()
    private val lexicon: Map<String, Map<String, String?>>

    init {
        val usGold = loadJsonDict(goldDictionaryPath)
        val usSilver = loadJsonDict(silverDictionaryPath)
        val gbGold = loadJsonDict(gbGoldDictionaryPath)
        val gbSilver = loadJsonDict(gbSilverDictionaryPath)
        val merged = gbSilver + gbGold + usSilver + usGold
        lexicon = if (fixesDictionaryPath != null) {
            deepMerge(merged, loadJsonDict(fixesDictionaryPath))
        } else {
            merged
        }
        logger.info(
            "Loaded {} dictionary entries ({} US gold, {} US silver, {} GB gold, {} GB silver)",
            lexicon.size,
            usGold.size,
            usSilver.size,
            gbGold.size,
            gbSilver.size,
        )
    }

    /**
     * Looks up a word's pronunciation, selecting the appropriate POS variant.
     *
     * Selection logic (matching misaki's `Lexicon.lookup`):
     * 1. If the entry is a simple DEFAULT-only map, return DEFAULT
     * 2. If [futureVowel] is null (no future context — sentence end) and entry has "None" key, use "None"
     * 3. If exact [posTag] matches a key, use it
     * 4. Normalize [posTag] via [parentTag] (VBD→VERB, NN→NOUN, JJ→ADJ, RB→ADV) and try again
     * 5. Fall back to DEFAULT
     *
     * @param word lowercase word to look up
     * @param posTag Penn Treebank POS tag from the tagger, or null
     * @param futureVowel true if next phoneme starts with a vowel, false if consonant, null if sentence-final
     * @return pronunciation string, or null if word is not in the lexicon
     */
    fun lookup(word: String, posTag: String?, futureVowel: Boolean?): String? {
        val entry = lexicon[word] ?: return null
        return selectVariant(entry, posTag, futureVowel)
    }

    /**
     * Returns true if [word] exists in the merged lexicon (any POS variant).
     *
     * @param word the word to check (case-sensitive)
     * @return true if the word has at least one pronunciation entry
     */
    fun contains(word: String): Boolean = word in lexicon

    /**
     * Selects the best pronunciation variant from a multi-POS entry.
     * Tries: None key (sentence-final) → exact tag → parent tag → DEFAULT.
     *
     * @param entry per-POS pronunciation map (e.g., `{"DEFAULT": "lˈIv", "VERB": "lˈɪv"}`)
     * @param posTag Penn Treebank or universal POS tag, or null
     * @param futureVowel future sound context (null = sentence-final)
     * @return selected pronunciation, or null if the entry maps to null for this POS
     */
    private fun selectVariant(entry: Map<String, String?>, posTag: String?, futureVowel: Boolean?): String? {
        // Single-variant entries only have DEFAULT — skip the cascade
        if (entry.size == 1) return entry[DEFAULT_KEY]

        // Sentence-final: use stressed "None" variant for function words (e.g., "there" → ðˈɛɹ)
        if (futureVowel == null && NONE_KEY in entry) return entry[NONE_KEY]

        // Exact POS match (e.g., VBD, NOUN)
        if (posTag != null && posTag in entry) return entry[posTag]

        // Normalized parent tag (e.g., VBD → VERB, JJS → ADJ)
        val parent = parentTag(posTag)
        if (parent != null && parent in entry) return entry[parent]

        return entry[DEFAULT_KEY]
    }

    /**
     * Parses a misaki JSON dictionary, preserving per-POS variant maps.
     * Simple string entries become `{"DEFAULT": phonemes}`. JSON `null` values (used for
     * POS keys where the word should not be pronounced, e.g., uppercase abbreviations as NOUN)
     * are preserved as `null`.
     *
     * @param path filesystem path to a misaki JSON dictionary file
     * @return map of word → POS-keyed pronunciation variants
     */
    private fun loadJsonDict(path: String): Map<String, Map<String, String?>> {
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(File(path).readText()) as JsonObject
        val dict = mutableMapOf<String, Map<String, String?>>()
        for ((key, value) in obj.entries) {
            val entry = when (value) {
                // Simple string: single pronunciation, no POS variants
                is JsonPrimitive -> mapOf(DEFAULT_KEY to value.jsonPrimitive.content)
                // Object: per-POS variants (may contain null for "do not pronounce" entries)
                is JsonObject -> value.entries.associate { (k, v) ->
                    k to if (v is JsonPrimitive && !v.isString && v.content == "null") null else v.jsonPrimitive.content
                }
                else -> mapOf(DEFAULT_KEY to value.toString())
            }
            dict[key] = entry
            addCaseVariant(dict, key, entry)
        }
        return dict
    }

    /**
     * Grows the dictionary with case variants (matching misaki's `Lexicon.grow_dictionary`).
     * Lowercase words get a capitalized variant; capitalized words get a lowercase variant.
     *
     * @param dict mutable dictionary to add case variants to
     * @param key the original dictionary key
     * @param entry the pronunciation entry to share with the case variant
     */
    private fun addCaseVariant(dict: MutableMap<String, Map<String, String?>>, key: String, entry: Map<String, String?>) {
        if (key.length < 2) return
        // lowercase → Capitalized (e.g., "hello" → "Hello")
        if (key == key.lowercase()) {
            val capitalized = key.replaceFirstChar { it.uppercaseChar() }
            if (capitalized != key && capitalized !in dict) {
                dict[capitalized] = entry
            }
            // Capitalized → lowercase (e.g., "Hello" → "hello")
        } else if (key == key.lowercase().replaceFirstChar { it.uppercaseChar() }) {
            val lower = key.lowercase()
            if (lower !in dict) {
                dict[lower] = entry
            }
        }
    }

    /**
     * Deep-merges [overrides] into [base] at the inner-map level.
     * Only the specified POS keys within each entry are overridden; existing keys are preserved.
     *
     * @param base the base dictionary to merge into
     * @param overrides corrections to apply on top of the base
     * @return merged dictionary with overrides applied
     */
    private fun deepMerge(
        base: Map<String, Map<String, String?>>,
        overrides: Map<String, Map<String, String?>>,
    ): Map<String, Map<String, String?>> {
        val result = base.toMutableMap()
        for ((word, fixes) in overrides) {
            val existing = result[word]
            result[word] = if (existing != null) existing + fixes else fixes
        }
        return result
    }
}

/**
 * Normalizes a Penn Treebank or universal POS tag to the parent category used as
 * dictionary keys: VBD/VBG/VBN/VBP/VBZ→VERB, NN/NNS/NNP→NOUN, JJ/JJR/JJS→ADJ, RB/RBR/RBS→ADV.
 * Tags already in parent form (VERB, NOUN, etc.) pass through unchanged.
 *
 * @param tag Penn Treebank POS tag (e.g., "VBD", "NNS") or universal tag (e.g., "VERB"), or null
 * @return normalized parent category, or null if [tag] is null
 */
internal fun parentTag(tag: String?): String? = when {
    tag == null -> null
    tag.startsWith("VB") -> "VERB"
    tag.startsWith("NN") -> "NOUN"
    tag.startsWith("JJ") -> "ADJ"
    tag.startsWith("RB") -> "ADV"
    tag.startsWith("ADV") -> "ADV"
    tag.startsWith("ADJ") -> "ADJ"
    else -> tag
}

private const val DEFAULT_KEY = "DEFAULT"
private const val NONE_KEY = "None"
