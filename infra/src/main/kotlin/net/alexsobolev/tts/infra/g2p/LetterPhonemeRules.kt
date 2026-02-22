package net.alexsobolev.tts.infra.g2p

/**
 * Context-sensitive phoneme rules for multi-character patterns, silent-e, vowels, and consonants.
 *
 * Misaki phoneme set (American English):
 * - Vowels: ɑ æ ʌ ɔ ə ɛ ɪ i ʊ u ɜ ɐ ᵻ
 * - Diphthongs: I(aɪ) W(aʊ) A(eɪ) O(oʊ) Y(ɔɪ)
 * - Consonants: b d f ɡ h j k l m n p ɹ s t v w z
 * - Special: ʃ ʒ ʧ ʤ θ ð ŋ ᵊ
 * - Stress: ˈ (primary) ˌ (secondary)
 */
internal class LetterPhonemeRules {
    /**
     * Tries multi-character pattern lookups from longest (5-char) to shortest (2-char),
     * including context-sensitive conditional patterns at each length.
     *
     * @param word lowercase English word
     * @param pos current position in the word
     * @return pair of (IPA phonemes, characters consumed), or null if no pattern matches
     */
    fun tryPatternLookup(word: String, pos: Int): Pair<String, Int>? = tryLookup(word, pos, 5, PATTERNS_5)
        ?: matchConditional4(word, pos)
        ?: tryLookup(word, pos, 4, PATTERNS_4)
        ?: matchConditional3(word, pos)
        ?: tryLookup(word, pos, 3, PATTERNS_3)
        ?: matchConditional2(word, pos)
        ?: tryLookup(word, pos, 2, PATTERNS_2)

    /**
     * Matches a vowel-consonant-e pattern at the end of a word (silent-e rule).
     * The vowel gets its "long" pronunciation (e.g., "a" → "A"/eɪ, "i" → "I"/aɪ).
     *
     * @param word lowercase English word
     * @param pos current position (must be exactly 3 chars from word end for a match)
     * @return pair of (long vowel + consonant phonemes, 3 chars consumed), or null if no match
     */
    fun trySilentE(word: String, pos: Int): Pair<String, Int>? {
        if (pos + 3 != word.length || word[pos + 2] != 'e') return null
        val vowel = word[pos]
        val consonant = word[pos + 1]
        if (vowel !in VOWELS_NO_Y || consonant in VOWELS_NO_Y || consonant == 'r') return null
        val longVowel = LONG_VOWELS[vowel] ?: return null
        return (longVowel + (CONSONANT_PHONEMES[consonant] ?: consonant.toString())) to 3
    }

    /**
     * Returns the phoneme for 'a' based on context: "ɔ" before consonant-l, null before 'w'
     * (handled as "aw" digraph elsewhere), "æ" otherwise.
     *
     * @param word lowercase English word
     * @param pos position of 'a' in the word
     * @param nextChar the character immediately after 'a', or null if word-final
     * @return IPA vowel phoneme, or null if 'a' is part of a digraph handled elsewhere
     */
    fun aPhoneme(word: String, pos: Int, nextChar: Char?): String? {
        val beforeConsonantL = nextChar == 'l' && word.getOrNull(pos + 2)?.let { it !in VOWELS_NO_Y } == true
        return if (beforeConsonantL) {
            "ɔ"
        } else if (nextChar == 'w') {
            null
        } else {
            "æ"
        }
    }

    /**
     * Returns the phoneme for 'e': "ɛ" in mid-word, "ᵻ" in "-es" after sibilants,
     * silent at word-end (for words > 2 chars), or "i" for short words like "be".
     *
     * @param word lowercase English word
     * @param pos position of 'e' in the word
     * @return IPA phoneme, or null if 'e' is silent
     */
    fun ePhoneme(word: String, pos: Int): String? = if (pos != word.length - 1) {
        val isEsEnding = pos == word.length - 2 && word.last() == 's'
        val prevChar = word.getOrNull(pos - 1)
        if (isEsEnding && prevChar != null && prevChar in SIBILANT_LETTERS) "ᵻ" else "ɛ"
    } else if (word.length > 2) {
        null
    } else {
        "i"
    }

    /**
     * Returns the phoneme for 'i': null when part of a silent-e pattern (handled by [trySilentE]),
     * "ɪ" otherwise.
     *
     * @param word lowercase English word
     * @param pos position of 'i' in the word
     * @param nextChar the character immediately after 'i', or null if word-final
     * @return "ɪ" or null if 'i' is part of a silent-e pattern
     */
    fun iPhoneme(word: String, pos: Int, nextChar: Char?): String? {
        val hasConsonantNext = nextChar != null && nextChar !in VOWELS_NO_Y
        val endsWithSilentE = word.getOrNull(pos + 2) == 'e' && pos + 3 == word.length
        return if (hasConsonantNext && endsWithSilentE) null else "ɪ"
    }

    /**
     * Returns the phoneme for 'y': consonant "j" at word start, vowel "i" at word end,
     * "ɪ" before consonants mid-word, or consonant "j" before vowels.
     *
     * @param word lowercase English word
     * @param pos position of 'y' in the word
     * @param nextChar the character immediately after 'y', or null if word-final
     * @return IPA phoneme for 'y'
     */
    fun yPhoneme(word: String, pos: Int, nextChar: Char?): String {
        val isWordEnd = pos == word.length - 1
        return when {
            pos == 0 -> "j" // consonant y at start
            isWordEnd -> "i" // happy, baby
            nextChar != null && nextChar !in VOWELS_NO_Y -> "ɪ" // system, gym
            else -> "j"
        }
    }

    /**
     * Returns the phoneme for 's': voiced "z" after vowels at word-end, "ʃ" before "ur",
     * voiceless "s" otherwise.
     *
     * @param word lowercase English word
     * @param pos position of 's' in the word
     * @return IPA phoneme for 's'
     */
    fun sPhoneme(word: String, pos: Int): String {
        val isWordEnd = pos == word.length - 1
        val prevChar = word.getOrNull(pos - 1)
        val nextChar = word.getOrNull(pos + 1)
        return when {
            isWordEnd && prevChar != null && prevChar in VOWELS_NO_Y -> "z"
            nextChar == 'u' && word.getOrNull(pos + 2) == 'r' -> "ʃ"
            else -> "s"
        }
    }

    /**
     * Attempts a fixed-length substring lookup in a pattern map.
     *
     * @param word lowercase English word
     * @param pos current position in the word
     * @param len number of characters to extract for the lookup key
     * @param map pattern-to-phoneme mapping table
     * @return pair of (phonemes, characters consumed), or null if no match
     */
    private fun tryLookup(
        word: String,
        pos: Int,
        len: Int,
        map: Map<String, String>,
    ): Pair<String, Int>? {
        if (pos + len > word.length) return null
        return map[word.substring(pos, pos + len)]?.let { it to len }
    }

    /**
     * Matches 4-character patterns that only apply at the end of a word (e.g., "ages", "ence").
     *
     * @param word lowercase English word
     * @param pos current position (must be exactly 4 chars from word end for a match)
     * @return pair of (phonemes, 4 chars consumed), or null if no match
     */
    private fun matchConditional4(word: String, pos: Int): Pair<String, Int>? {
        if (pos + 4 != word.length) return null
        return WORD_END_4[word.substring(pos, pos + 4)]
    }

    /**
     * Matches 3-character patterns that only apply at the end of a word (e.g., "are", "ive", "ate").
     *
     * @param word lowercase English word
     * @param pos current position (must be exactly 3 chars from word end for a match)
     * @return pair of (phonemes, 3 chars consumed), or null if no match
     */
    private fun matchConditional3(word: String, pos: Int): Pair<String, Int>? {
        if (pos + 3 != word.length) return null
        return when (word.substring(pos, pos + 3)) {
            "are" -> "ɛɹ" to 3
            "age" -> "ɪʤ" to 3
            "ive" -> "ɪv" to 3
            "ate" -> if (word.length > 4) "At" to 3 else null
            else -> null
        }
    }

    /**
     * Matches context-sensitive 2-character patterns: "th" digraph, "gn"/"gh" digraphs,
     * "ow" diphthong, and word-final "ey".
     *
     * @param word lowercase English word
     * @param pos current position in the word
     * @return pair of (phonemes, 2 chars consumed), or null if no match
     */
    private fun matchConditional2(word: String, pos: Int): Pair<String, Int>? {
        if (pos + 2 > word.length) return null
        return when (word[pos]) {
            't' -> if (word[pos + 1] == 'h') thDigraph(word, pos) else null
            'g' -> gDigraph(word, pos)
            'o' -> if (word[pos + 1] == 'w') owDigraph(word, pos) else null
            'e' -> if (word[pos + 1] == 'y' && pos + 2 == word.length) "i" to 2 else null
            else -> null
        }
    }

    /**
     * Resolves "gn" and "gh" digraphs: word-initial "gn" → "n", "gh" → "ɡ" at start / silent elsewhere.
     */
    private fun gDigraph(word: String, pos: Int): Pair<String, Int>? = when (word[pos + 1]) {
        'n' -> if (pos == 0) "n" to 2 else null
        'h' -> if (pos == 0) "ɡ" to 2 else "" to 2
        else -> null
    }

    /**
     * Resolves "ow": diphthong "W" (aʊ) before n/l/e or at word-end, long "O" (oʊ) otherwise.
     */
    private fun owDigraph(word: String, pos: Int): Pair<String, Int> =
        if (pos + 2 == word.length || word.getOrNull(pos + 2) in OW_DIPHTHONG_NEXT) "W" to 2 else "O" to 2

    /**
     * Resolves "th": voiced "ð" at word start before a vowel (e.g., "the"), voiceless "θ" otherwise.
     */
    private fun thDigraph(word: String, pos: Int): Pair<String, Int> =
        if (pos == 0 && pos + 2 < word.length && word[pos + 2] in VOWELS) "ð" to 2 else "θ" to 2
}

// Pattern lookup tables

private val PATTERNS_5 =
    mapOf(
        "ought" to "ɔt",
        "ation" to "Aʃən",
    )

private val PATTERNS_4 =
    mapOf(
        "tion" to "ʃən",
        "sion" to "ʒən",
        "ious" to "iəs",
        "eous" to "iəs",
        "ight" to "It",
        "ough" to "O",
        "ture" to "ʧəɹ",
        "sure" to "ʒəɹ",
        "tial" to "ʃəl",
        "cial" to "ʃəl",
    )

private val PATTERNS_3 =
    mapOf(
        "tch" to "ʧ",
        "dge" to "ʤ",
        "sch" to "sk",
        "scr" to "skɹ",
        "shr" to "ʃɹ",
        "str" to "stɹ",
        "thr" to "θɹ",
        "chr" to "kɹ",
        "air" to "ɛɹ",
        "ear" to "iɹ",
        "eer" to "iɹ",
        "our" to "Wɹ",
        "oor" to "ɔɹ",
        "ore" to "ɔɹ",
        "ire" to "Iɹ",
        "ure" to "jʊɹ",
        "ous" to "əs",
        "ess" to "ɛs",
        "ing" to "ɪŋ",
        "ble" to "bəl",
        "ple" to "pəl",
        "tle" to "təl",
        "dle" to "dəl",
        "gle" to "ɡəl",
        "kle" to "kəl",
        "ful" to "fəl",
        "all" to "ɔl",
        "alk" to "ɔk",
        "ism" to "ɪzəm",
        "ist" to "ɪst",
        "ity" to "ɪti",
        "ily" to "ɪli",
        "ize" to "Iz",
        "ise" to "Iz",
        "ary" to "ɛɹi",
        "ery" to "ɛɹi",
        "ory" to "ɔɹi",
    )

private val PATTERNS_2 =
    mapOf(
        "sh" to "ʃ",
        "ch" to "ʧ",
        "ph" to "f",
        "wh" to "w",
        "wr" to "ɹ",
        "kn" to "n",
        "ck" to "k",
        "ng" to "ŋ",
        "nk" to "ŋk",
        "qu" to "kw",
        "ee" to "i",
        "ea" to "i",
        "ai" to "A",
        "ay" to "A",
        "oa" to "O",
        "ou" to "W",
        "oo" to "u",
        "oi" to "Y",
        "oy" to "Y",
        "ie" to "i",
        "aw" to "ɔ",
        "au" to "ɔ",
        "ew" to "ju",
    )

private val WORD_END_4 =
    mapOf(
        "ages" to ("ᵻʤᵻz" to 4),
        "ares" to ("ɛɹz" to 4),
        "ives" to ("ɪvz" to 4),
        "ence" to ("əns" to 4),
        "ance" to ("əns" to 4),
    )

private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')
private val VOWELS_NO_Y = setOf('a', 'e', 'i', 'o', 'u')
private val LONG_VOWELS =
    mapOf(
        'a' to "A",
        'e' to "i",
        'i' to "I",
        'o' to "O",
        'u' to "ju",
    )

private val CONSONANT_PHONEMES =
    mapOf(
        'b' to "b",
        'c' to "k",
        'd' to "d",
        'f' to "f",
        'g' to "ɡ",
        'h' to "h",
        'j' to "ʤ",
        'k' to "k",
        'l' to "l",
        'm' to "m",
        'n' to "n",
        'p' to "p",
        'r' to "ɹ",
        's' to "s",
        't' to "t",
        'v' to "v",
        'w' to "w",
        'x' to "ks",
        'y' to "j",
        'z' to "z",
    )

private val OW_DIPHTHONG_NEXT = setOf('n', 'l', 'e')
private val SIBILANT_LETTERS = setOf('g', 'c', 's', 'x', 'z', 'j')
