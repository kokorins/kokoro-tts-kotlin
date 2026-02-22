package net.alexsobolev.tts.infra.g2p

import net.alexsobolev.tts.core.api.PhonemeGenerator
import net.alexsobolev.tts.core.utils.logger
import net.alexsobolev.tts.domain.PhonemeSequence

/**
 * English grapheme-to-phoneme converter using POS-aware dictionary lookup.
 *
 * Uses OpenNLP POS tagging to select context-appropriate pronunciations for words with
 * multiple POS variants (e.g., "live" as adjective vs verb, "record" as noun vs verb).
 * Words not found in any dictionary fall back to [LetterPhonemeConverter] rules.
 * Dictionaries are sourced from [Misaki](https://github.com/hexgrad/misaki).
 */
internal class EnglishPhonemeGenerator(
    private val lexicon: PosAwareLexicon,
    private val fallback: LetterPhonemeConverter,
    private val numberExpander: NumberExpander,
    private val posTagger: PosTagger,
) : PhonemeGenerator {
    private val logger = logger()

    override suspend fun generate(text: String): PhonemeSequence {
        val expanded = CONTRACTIONS.fold(text) { acc, (pattern, replacement) -> acc.replace(pattern, replacement) }
        val (processedText, annotations) = extractAnnotations(expanded)
        val rawTokens = processedText.split(WHITESPACE).filter { it.isNotBlank() }

        val cleanWords = rawTokens.map { token ->
            token.dropLastWhile { it in PUNCTUATION }.lowercase().replace(NON_ALPHA, "")
        }
        // POS tagger needs original case — "Yesterday I read" tags differently from "yesterday i read"
        val posTokens = rawTokens.map { token ->
            token.dropLastWhile { it in PUNCTUATION }.replace(NON_ALPHA_PRESERVING_CASE, "")
        }
        val posTags = tagTokens(posTokens)

        val phonemes = resolveAllTokens(rawTokens, cleanWords, posTags, annotations)

        val result = phonemes.ifBlank { " " }
        return PhonemeSequence(result)
    }

    /**
     * Two-pass phoneme resolution:
     * 1. Resolve each token to phonemes using POS-aware dictionary lookup (futureVowel unknown)
     * 2. Reverse-scan to compute futureVowel, then apply context overrides (the/to/a, None-key re-lookup)
     *
     * @param rawTokens whitespace-split tokens, potentially including trailing punctuation
     * @param cleanWords lowercase, punctuation-stripped versions of [rawTokens]
     * @param posTags POS tag for each token, from [tagTokens]
     * @param annotations inline phoneme overrides extracted by [extractAnnotations]
     * @return concatenated phoneme string with punctuation preserved
     */
    private fun resolveAllTokens(
        rawTokens: List<String>,
        cleanWords: List<String>,
        posTags: List<String>,
        annotations: Map<String, String>,
    ): String {
        // First pass: resolve each token to phonemes (futureVowel unknown at this stage)
        val resolvedPhonemes = arrayOfNulls<String>(rawTokens.size)
        for ((index, token) in rawTokens.withIndex()) {
            val rawWord = token.dropLastWhile { it in PUNCTUATION }
            if (rawWord.isBlank()) continue
            val inlinePhonemes = annotations[rawWord]
            resolvedPhonemes[index] = inlinePhonemes ?: resolveWord(rawWord, posTags[index])
        }

        // Second pass: compute futureVowel context, then re-resolve context-dependent words
        val futureVowels = computeFutureVowels(resolvedPhonemes)
        val phonemeString = StringBuilder()

        for ((index, token) in rawTokens.withIndex()) {
            val punctuation = token.takeLastWhile { it in PUNCTUATION }
            val rawWord = token.dropLastWhile { it in PUNCTUATION }

            if (rawWord.isNotBlank()) {
                var phonemes = resolvedPhonemes[index]
                phonemes = applyContextOverrides(cleanWords[index], phonemes, posTags[index], futureVowels[index])
                if (phonemes != null) {
                    if (phonemeString.isNotEmpty()) phonemeString.append(" ")
                    phonemeString.append(phonemes)
                } else {
                    logger.warn("Skipping word with no phoneme mapping: '{}'", rawWord)
                }
            }

            if (punctuation.isNotBlank()) {
                phonemeString.append(punctuation)
            }
        }
        return phonemeString.toString()
    }

    /**
     * POS-tags tokens, substituting "." for blanks so the tagger has valid input.
     * Tokens should preserve original case — POS taggers use case features for accuracy.
     *
     * @param tokens word tokens with original casing, punctuation stripped
     * @return POS tags aligned with [tokens]
     */
    private fun tagTokens(tokens: List<String>): List<String> {
        val taggable = tokens.map { it.ifBlank { "." } }
        return if (taggable.isEmpty()) emptyList() else posTagger.tag(taggable)
    }

    /**
     * Computes future vowel context for each token by reverse-scanning resolved phonemes.
     *
     * For each position, `futureVowel` is:
     * - `true` if the next token's phonemes start with a vowel sound
     * - `false` if the next token's phonemes start with a consonant sound
     * - `null` if at sentence end or no future phoneme context is available
     *
     * @param resolvedPhonemes first-pass phoneme results, one per token (null if unresolved)
     * @return per-token futureVowel context aligned with [resolvedPhonemes]
     */
    private fun computeFutureVowels(resolvedPhonemes: Array<String?>): Array<Boolean?> {
        val result = arrayOfNulls<Boolean>(resolvedPhonemes.size)
        var lastVowelInfo: Boolean? = null
        // Scan right-to-left: each position gets the vowel info from the next token
        for (i in resolvedPhonemes.indices.reversed()) {
            result[i] = lastVowelInfo
            val ps = resolvedPhonemes[i]
            if (!ps.isNullOrBlank()) {
                // Skip stress marks (ˈ, ˌ) to find the actual first vowel or consonant sound
                val firstSound = ps.firstOrNull { it in IPA_VOWELS || it in IPA_CONSONANTS }
                if (firstSound != null) {
                    lastVowelInfo = firstSound in IPA_VOWELS
                }
            }
        }
        return result
    }

    /**
     * Applies context-dependent overrides for function words after all phonemes are resolved.
     * This runs in a second pass so that `futureVowel` context is available.
     *
     * @param cleanWord lowercase, punctuation-stripped word
     * @param phonemes first-pass phoneme result (may be overridden)
     * @param posTag POS tag from [tagTokens]
     * @param futureVowel whether the next word starts with a vowel, consonant, or is sentence-final
     * @return final phonemes for this word
     */
    private fun applyContextOverrides(
        cleanWord: String,
        phonemes: String?,
        posTag: String,
        futureVowel: Boolean?,
    ): String? {
        // Function word overrides: pronunciation depends on the following sound
        if (cleanWord == "the") return if (futureVowel == true) "ði" else "ðə"
        if (cleanWord == "to") return resolveToPhoneme(posTag, futureVowel)
        if (cleanWord == "a" && posTag == "DT") return "ɐ"
        // Re-lookup with correct futureVowel for sentence-final stressed forms (None key)
        return lexicon.lookup(cleanWord, posTag, futureVowel) ?: phonemes
    }

    /**
     * Resolves "to" pronunciation based on POS tag and following sound context.
     * Matching misaki: reduced "tə" before consonants, "tʊ" before vowels, full "tu" at sentence end.
     *
     * @param posTag POS tag for the word "to" (TO = infinitive marker, IN = preposition)
     * @param futureVowel whether the next word starts with a vowel, consonant, or is sentence-final
     * @return IPA phonemes for "to"
     */
    private fun resolveToPhoneme(posTag: String, futureVowel: Boolean?): String = when {
        posTag == "TO" || posTag == "IN" -> when (futureVowel) {
            null -> "tu"
            true -> "tʊ"
            false -> "tə"
        }
        else -> "tu"
    }

    /**
     * Extracts `(word or phrase)[IPA phonemes]` annotations from text, replacing each
     * with a null-prefixed placeholder token. Returns cleaned text + placeholder→phoneme map.
     *
     * @param text input text potentially containing inline phoneme annotations
     * @return pair of (cleaned text with placeholders, map of placeholder→phonemes)
     */
    private fun extractAnnotations(text: String): Pair<String, Map<String, String>> {
        val annotations = mutableMapOf<String, String>()
        var index = 0
        val cleaned =
            PHONEME_ANNOTATION.replace(text) { match ->
                val phonemes = match.groupValues[2].replace(".", "")
                val key = "$PLACEHOLDER_PREFIX${index++}"
                annotations[key] = phonemes
                key
            }
        return cleaned to annotations
    }

    /**
     * First-pass word resolution: abbreviations → numbers → dictionary/fallback.
     * "the" and "to" return placeholder strings (resolved in second pass with futureVowel context).
     *
     * @param rawWord original token with casing preserved, trailing punctuation stripped
     * @param posTag POS tag assigned to this token
     * @return IPA phonemes, a placeholder string for context-dependent words, or null
     */
    private fun resolveWord(rawWord: String, posTag: String): String? {
        if (isAbbreviation(rawWord)) return fallback.convertAbbreviation(rawWord)

        val expanded = numberExpander.expand(rawWord)
        if (expanded != null) {
            return expanded.split(" ")
                .mapNotNull { word -> lookupWithFallback(word, null) }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
        }

        val cleanWord = rawWord.lowercase().replace(NON_ALPHA, "")
        if (cleanWord.isBlank()) return null
        if (cleanWord == "the" || cleanWord == "to") return cleanWord
        return lookupWithFallback(cleanWord, posTag)
    }

    /**
     * Detects abbreviations: 2+ uppercase letters, optionally mixed with digits (e.g., "NASA", "MP3").
     *
     * @param word raw token with original casing
     * @return true if the word should be spelled out letter-by-letter
     */
    private fun isAbbreviation(word: String): Boolean {
        if (word.length < 2) return false
        var upperCount = 0
        for (ch in word) {
            if (ch in 'A'..'Z') {
                upperCount++
            } else if (ch !in '0'..'9') {
                return false
            }
        }
        return upperCount >= 2
    }

    /**
     * Tries lexicon → possessive → stemmed → compound → letter-rule fallback, in order.
     *
     * @param word lowercase word to look up
     * @param posTag POS tag for POS-aware dictionary selection, or null if unavailable
     * @return IPA phonemes, or null if all strategies fail
     */
    private fun lookupWithFallback(word: String, posTag: String?): String? = lexicon.lookup(word, posTag, true)
        ?: lookupPossessive(word, posTag)
        ?: lookupStemmed(word, posTag)
        ?: lookupCompound(word, posTag)
        ?: fallback.convert(word)

    /**
     * Strips "'s" suffix, looks up the base word, and appends the appropriate sibilant suffix.
     *
     * @param word lowercase word ending in "'s"
     * @param posTag POS tag for POS-aware dictionary selection
     * @return base phonemes + possessive suffix, or null if the word doesn't end in "'s" or base is unknown
     */
    private fun lookupPossessive(word: String, posTag: String?): String? {
        if (!word.endsWith("'s") || word.length <= 2) return null
        val base = lookupWithFallback(word.dropLast(2), posTag) ?: return null
        return base + pluralSuffix(base)
    }

    /**
     * Tries inflectional and derivational stem candidates, returning the first match + suffix.
     *
     * @param word lowercase inflected word
     * @param posTag POS tag for POS-aware dictionary selection
     * @return stem phonemes + suffix phonemes, or null if no stem candidate is in the lexicon
     */
    private fun lookupStemmed(word: String, posTag: String?): String? {
        for ((stem, suffixFn) in stemCandidates(word)) {
            val stemPhonemes = lexicon.lookup(stem, posTag, true)
            if (stemPhonemes != null) return suffixFn(stemPhonemes)
        }
        return null
    }

    /**
     * Splits a compound word at every position (min 3 chars per part) and looks up both halves.
     * The second part's primary stress is demoted to secondary for natural compound pronunciation.
     *
     * @param word lowercase compound word candidate
     * @param posTag POS tag for POS-aware dictionary selection
     * @return combined phonemes with demoted stress on the second part, or null if no split works
     */
    private fun lookupCompound(word: String, posTag: String?): String? {
        for (i in 3..word.length - 3) {
            val firstPhonemes = lexicon.lookup(word.substring(0, i), posTag, true) ?: continue
            val secondPhonemes = lexicon.lookup(word.substring(i), posTag, true)
                ?: lookupStemmed(word.substring(i), posTag)
            if (secondPhonemes != null) {
                // Demote primary stress (ˈ) to secondary (ˌ) on the second component
                val reduced =
                    if (secondPhonemes.startsWith("\u02C8")) {
                        "\u02CC" + secondPhonemes.removePrefix("\u02C8")
                    } else {
                        secondPhonemes.replace("\u02C8", "")
                    }
                return firstPhonemes + reduced
            }
        }
        return null
    }
}

/**
 * Builds a list of (candidate stem, suffix-application function) pairs for morphological lookup.
 *
 * @param word the inflected or derived word to generate stem candidates for
 * @return list of (stem, suffix function) pairs to try against the lexicon
 */
private fun stemCandidates(word: String): List<Pair<String, (String) -> String>> = buildList {
    inflectionalCandidates(word)
    derivationalCandidates(word)
}

/**
 * Generates candidates for plural -s/-es/-ies, past tense -ed, and progressive -ing.
 *
 * @param word the inflected word to strip suffixes from
 */
private fun MutableList<Pair<String, (String) -> String>>.inflectionalCandidates(word: String) {
    // Plural / 3rd-person singular -s (not -ss, to avoid "less" → "les" + s)
    if (word.endsWith("s") && !word.endsWith("ss") && word.length > 3) {
        add(word.dropLast(1) to { base: String -> base + pluralSuffix(base) })
    }
    // Plural -ies → y (e.g., "replies" → "reply")
    if (word.endsWith("ies") && word.length > 4) {
        add((word.dropLast(3) + "y") to { base: String -> base + pluralSuffix(base) })
    }
    // Plural -es (e.g., "churches" → "church")
    if (word.endsWith("es") && word.length > 4) {
        add(word.dropLast(2) to { base: String -> base + "ᵻz" })
    }
    // Past tense -ed: try base+e first (e.g., "averaged" → "average"), then base (e.g., "passed" → "pass")
    if (word.endsWith("ed") && word.length > 4) {
        add(word.dropLast(1) to ::applyEdSuffix)
        add(word.dropLast(2) to ::applyEdSuffix)
    }
    // Progressive -ing: try base, then base+e (e.g., "averaging" → "average"), then doubled consonant
    if (word.endsWith("ing") && word.length > 5) {
        add(word.dropLast(3) to ::applyIngSuffix)
        add((word.dropLast(3) + "e") to ::applyIngSuffix)
        addDoubledConsonantIng(word)
    }
}

/**
 * Handles doubled consonant in -ing forms (e.g., "running" -> "run", "stocking" -> "stock").
 * Matches misaki's regex: `([bcdgklmnprstvxz])\1ing$|cking$`
 *
 * @param word the -ing word with potentially doubled final consonant
 */
private fun MutableList<Pair<String, (String) -> String>>.addDoubledConsonantIng(word: String) {
    if (word.length <= 6) return
    if (word.endsWith("cking")) {
        add(word.dropLast(4) to ::applyIngSuffix)
    } else {
        val beforeIng = word[word.length - 4]
        if (beforeIng in DOUBLED_ING_CONSONANTS && word[word.length - 5] == beforeIng) {
            add(word.dropLast(4) to ::applyIngSuffix)
        }
    }
}

/**
 * Generates candidates for derivational suffixes: adverbial -ly, agent noun -er, and privative -less.
 *
 * @param word the derived word to strip suffixes from
 */
private fun MutableList<Pair<String, (String) -> String>>.derivationalCandidates(word: String) {
    if (word.endsWith("ly") && word.length > 4) {
        add(word.dropLast(2) to { base: String -> base + "li" })
    }
    if (word.endsWith("er") && word.length > 4) {
        add(word.dropLast(2) to { base: String -> base + "əɹ" })
        add((word.dropLast(2) + "e") to { base: String -> base + "əɹ" })
    }
    if (word.endsWith("less") && word.length > 6) {
        add(word.dropLast(4) to { base: String -> base + "ləs" })
    }
}

/**
 * Returns the phonological plural/possessive suffix based on the final sound of [basePhonemes]:
 * sibilants get "ᵻz", voiceless consonants get "s", everything else gets "z".
 *
 * @param basePhonemes IPA phonemes of the base word
 * @return the appropriate plural/possessive suffix
 */
private fun pluralSuffix(basePhonemes: String): String = when (basePhonemes.last()) {
    in SIBILANTS -> "ᵻz"
    in VOICELESS -> "s"
    else -> "z"
}

/**
 * Applies -ed past tense suffix with US English T-flapping (matching misaki's `_ed` logic).
 * Stem-final 't' preceded by a vowel in [US_TAUS] flaps to 'ɾ' (e.g., "waited" -> wAɾᵻd).
 *
 * @param basePhonemes IPA phonemes of the stem
 * @return stem phonemes with the appropriate -ed suffix applied
 */
private fun applyEdSuffix(basePhonemes: String): String {
    val last = basePhonemes.last()
    return when {
        last == 't' && basePhonemes.length >= 2 && basePhonemes[basePhonemes.length - 2] in US_TAUS ->
            basePhonemes.dropLast(1) + "ɾᵻd"
        last == 't' || last == 'd' -> basePhonemes + "ᵻd"
        last in VOICELESS -> basePhonemes + "t"
        else -> basePhonemes + "d"
    }
}

/**
 * Applies -ing progressive suffix with US English T-flapping (matching misaki's `_ing` logic).
 * Stem-final 't' preceded by a vowel in [US_TAUS] flaps to 'ɾ' (e.g., "writing" -> ɹIɾɪŋ).
 *
 * @param basePhonemes IPA phonemes of the stem
 * @return stem phonemes with the appropriate -ing suffix applied
 */
private fun applyIngSuffix(basePhonemes: String): String {
    if (basePhonemes.length >= 2 && basePhonemes.last() == 't' && basePhonemes[basePhonemes.length - 2] in US_TAUS) {
        return basePhonemes.dropLast(1) + "ɾɪŋ"
    }
    return basePhonemes + "ɪŋ"
}

private val WHITESPACE = Regex("\\s+")
private val NON_ALPHA = Regex("[^a-z']")
private val NON_ALPHA_PRESERVING_CASE = Regex("[^a-zA-Z']")
private val PUNCTUATION = setOf('.', ',', '!', '?', ';', ':')

// IPA sibilant consonants — plurals after these get the "ᵻz" suffix
private val SIBILANTS = setOf('s', 'z', 'ʃ', 'ʒ', 'ʧ', 'ʤ')

// IPA voiceless consonants — plurals get "s", past tense gets "t"
private val VOICELESS = setOf('p', 't', 'k', 'f', 'θ', 's', 'ʃ', 'ʧ')
private val CONTRACTIONS =
    listOf(
        Regex("""\bI'm\b""") to "I am",
    )

// Matches "(word or phrase)[IPA phonemes]" inline annotations
private val PHONEME_ANNOTATION = Regex("""\(([^)]+)\)\[([^]]+)]""")

// Null-byte prefix ensures placeholders never collide with real words
private const val PLACEHOLDER_PREFIX = "\u0000PH_"

// Consonants that can double before -ing (e.g., "running" has nn, "sitting" has tt)
private val DOUBLED_ING_CONSONANTS = setOf(
    'b', 'c', 'd', 'g', 'k', 'l', 'm', 'n', 'p', 'r', 's', 't', 'v', 'x', 'z',
)

// Misaki IPA vowels — includes uppercase diphthong symbols (A=eɪ, I=aɪ, O=oʊ, W=aʊ, Y=ɔɪ)
private val IPA_VOWELS = setOf(
    'A', 'I', 'O', 'W', 'Y',
    'a', 'i', 'u', 'æ', 'ɑ', 'ɒ', 'ɔ', 'ə', 'ɛ', 'ɜ', 'ɪ', 'ʊ', 'ʌ', 'ᵻ',
)

// Misaki IPA consonants — used to identify first sound in futureVowel computation
private val IPA_CONSONANTS = setOf(
    'b', 'd', 'f', 'h', 'j', 'k', 'l', 'm', 'n', 'p', 's', 't', 'v', 'w', 'z',
    'ð', 'ŋ', 'ɡ', 'ɹ', 'ɾ', 'ʃ', 'ʒ', 'ʤ', 'ʧ', 'θ',
)

// US English T-flapping context: vowels/ɹ before which stem-final 't' becomes 'ɾ' in -ed/-ing
private val US_TAUS = setOf(
    'A', 'I', 'O', 'W', 'Y', 'i', 'u', 'æ', 'ɑ', 'ə', 'ɛ', 'ɪ', 'ɹ', 'ʊ', 'ʌ',
)
