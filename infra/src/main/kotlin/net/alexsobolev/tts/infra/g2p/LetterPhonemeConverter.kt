package net.alexsobolev.tts.infra.g2p

import net.alexsobolev.tts.core.utils.logger

/**
 * Rule-based English letter-to-phoneme converter.
 *
 * Converts English words into Misaki/Kokoro IPA phonemes using pattern matching rules.
 * This serves as a fallback when a word is not found in the gold/silver dictionaries.
 * The output is approximate since English spelling is highly irregular, but it produces
 * valid phoneme sequences that the Kokoro model can process, which is far better than
 * passing raw English letters through.
 *
 * Stress is placed on the penultimate (second to last) syllable for words with two or
 * more syllables, which is the most common pattern in English.
 */
internal class LetterPhonemeConverter(private val rules: LetterPhonemeRules) {
    private val logger = logger()

    /**
     * Converts an English word to Misaki phonemes using letter rules.
     *
     * @param word The English word in lowercase, with no punctuation.
     * @return The phoneme string, or null if the word cannot be converted.
     */
    fun convert(word: String): String? {
        if (word.isBlank()) return null

        val cleaned = word.lowercase().trim()
        if (cleaned.any { it !in 'a'..'z' && it != '\'' }) return null

        val phonemes = applyRules(cleaned) ?: return null
        if (phonemes.isBlank()) return null

        val stressed = applyStress(phonemes)

        logger.debug("Letter rules fallback: '{}' -> '{}'", word, stressed)
        return stressed
    }

    /**
     * Converts an all-uppercase abbreviation (optionally containing digits) into
     * letter-by-letter phonemes with appropriate stress.
     *
     * @param word the abbreviation in uppercase, e.g. "TTS", "MP3"
     * @return the phoneme string, or null if the word is not a valid abbreviation
     */
    fun convertAbbreviation(word: String): String? {
        if (word.length < 2) return null
        if (word.any { it !in 'A'..'Z' && it !in '0'..'9' }) return null
        if (word.count { it in 'A'..'Z' } < 2) return null

        val runs = splitLetterDigitRuns(word)
        val parts =
            runs.map { run ->
                if (run.first() in 'A'..'Z') {
                    spellLetters(run)
                } else {
                    run.map { DIGIT_NAMES[it] ?: "" }.joinToString(" ")
                }
            }
        val result = parts.joinToString(" ").trim()
        logger.debug("Abbreviation fallback: '{}' -> '{}'", word, result)
        return result.ifBlank { null }
    }

    /**
     * Walks through the word left-to-right, matching the longest phoneme pattern at each position.
     *
     * @param word lowercase English word
     * @return raw phoneme string (without stress), or null if empty
     */
    private fun applyRules(word: String): String? {
        val result = StringBuilder()
        var i = 0

        while (i < word.length) {
            val consumed = matchPattern(word, i, result)
            if (consumed == 0) {
                i++
            } else {
                i += consumed
            }
        }

        return result.toString().ifBlank { null }
    }

    /**
     * Tries multi-char patterns, then silent-e, then single-char rules at [pos].
     *
     * @param word lowercase English word
     * @param pos current position in the word
     * @param result builder to append matched phonemes to
     * @return number of characters consumed (1 if nothing matched, to advance past the character)
     */
    private fun matchPattern(word: String, pos: Int, result: StringBuilder): Int {
        // Try longest patterns first (5-char), then progressively shorter, then single char
        val match =
            rules.tryPatternLookup(word, pos)
                ?: rules.trySilentE(word, pos)
                ?: trySingleChar(word, pos)
        if (match != null) {
            result.append(match.first)
            return match.second
        }
        return 1
    }

    /**
     * Matches a single character to its phoneme, consuming doubled consonants as one unit.
     *
     * @param word lowercase English word
     * @param pos current position in the word
     * @return pair of (phoneme, characters consumed), or null if the character has no mapping
     */
    private fun trySingleChar(word: String, pos: Int): Pair<String, Int>? {
        val ch = word[pos]
        val phoneme = singleCharacter(ch, word, pos) ?: return null
        // Doubled consonants (e.g., "ll", "tt") produce a single phoneme
        val consumed = if (pos + 1 < word.length && word[pos + 1] == ch && ch in DOUBLED_CONSONANTS) 2 else 1
        return phoneme to consumed
    }

    /**
     * Maps a single character to its IPA phoneme based on vowel/consonant classification.
     *
     * @param ch the character to convert
     * @param word full word for context
     * @param pos position of [ch] in [word]
     * @return IPA phoneme string, or null if the character should be silent
     */
    private fun singleCharacter(ch: Char, word: String, pos: Int): String? {
        if (ch in VOWELS) return vowelPhoneme(ch, word, pos)
        if (ch == '\'') return null
        SIMPLE_CONSONANTS[ch]?.let { return it }
        return contextConsonant(ch, word, pos)
    }

    /**
     * Selects the IPA phoneme for a vowel character, considering neighboring letters
     * (e.g., vowel-before-r adjustments, 'a' before consonant-l).
     *
     * @param ch the vowel character (a, e, i, o, u, or y)
     * @param word full word for context
     * @param pos position of [ch] in [word]
     * @return IPA vowel phoneme, or null if the vowel is silent in this context
     */
    private fun vowelPhoneme(ch: Char, word: String, pos: Int): String? {
        val nextChar = word.getOrNull(pos + 1)
        if (nextChar == 'r') {
            VOWEL_BEFORE_R[ch]?.let { return it }
        }
        return when (ch) {
            'a' -> rules.aPhoneme(word, pos, nextChar)
            'e' -> rules.ePhoneme(word, pos)
            'i' -> rules.iPhoneme(word, pos, nextChar)
            'o' -> if (pos == word.length - 1) "O" else "ɑ"
            'u' -> {
                val prevChar = word.getOrNull(pos - 1)
                if (prevChar != null && prevChar in U_LONG_PREV) "u" else "ʌ"
            }
            'y' -> rules.yPhoneme(word, pos, nextChar)
            else -> null
        }
    }

    /**
     * Maps context-sensitive consonants (c, g, s, x, h) to their IPA phoneme.
     * For example, 'c' before 'e'/'i'/'y' is "s" (soft), otherwise "k" (hard).
     *
     * @param ch the consonant character
     * @param word full word for context
     * @param pos position of [ch] in [word]
     * @return IPA consonant phoneme, or null if the consonant is silent
     */
    private fun contextConsonant(ch: Char, word: String, pos: Int): String? {
        val nextChar = word.getOrNull(pos + 1)
        val prevChar = word.getOrNull(pos - 1)
        return when (ch) {
            'c' -> if (nextChar != null && nextChar in SOFT_VOWELS) "s" else "k"
            'g' -> if (nextChar != null && nextChar in SOFT_VOWELS) "ʤ" else "ɡ"
            's' -> rules.sPhoneme(word, pos)
            'x' -> if (pos == 0) "z" else "ks"
            'h' -> if (prevChar != null && prevChar in SILENT_H_PREV) null else "h"
            else -> null
        }
    }

    /**
     * Adds primary stress to the penultimate syllable (most common English stress pattern).
     * Single-syllable words get stress on their only syllable.
     *
     * @param phonemes raw IPA phoneme string without stress marks
     * @return phoneme string with primary stress mark (ˈ) inserted
     */
    private fun applyStress(phonemes: String): String {
        val syllableStarts = mutableListOf<Int>()
        var inVowel = false
        for ((idx, ch) in phonemes.withIndex()) {
            if (ch in VOWEL_CHARS) {
                if (!inVowel) {
                    syllableStarts.add(idx)
                    inVowel = true
                }
            } else {
                inVowel = false
            }
        }

        if (syllableStarts.isEmpty()) return phonemes

        val stressPos =
            if (syllableStarts.size <= 1) {
                syllableStarts[0]
            } else {
                syllableStarts[syllableStarts.size - 2]
            }

        return StringBuilder(phonemes.length + 2).apply {
            append(phonemes, 0, stressPos)
            append('ˈ')
            append(phonemes, stressPos, phonemes.length)
        }.toString()
    }

    /**
     * Splits an abbreviation into alternating runs of letters and digits (e.g., "MP3" → ["MP", "3"]).
     *
     * @param word uppercase abbreviation potentially containing digits
     * @return list of letter-only and digit-only substrings
     */
    private fun splitLetterDigitRuns(word: String): List<String> {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        var currentIsLetter = word.first() in 'A'..'Z'

        for (ch in word) {
            val isLetter = ch in 'A'..'Z'
            if (isLetter != currentIsLetter) {
                runs.add(current.toString())
                current.clear()
                currentIsLetter = isLetter
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) runs.add(current.toString())
        return runs
    }

    /**
     * Converts a run of uppercase letters to IPA by looking up each letter's name.
     * The last letter gets primary stress (ˈ); preceding letters get secondary stress (ˌ).
     *
     * @param letters uppercase letter string (e.g., "TTS")
     * @return IPA phonemes for the spelled-out letters
     */
    private fun spellLetters(letters: String): String {
        val letterPhonemes = letters.lowercase().map { LETTER_NAMES[it] ?: "" }
        return letterPhonemes.mapIndexed { index, phoneme ->
            val stress = if (index == letterPhonemes.size - 1) "ˈ" else "ˌ"
            insertStressBeforeVowel(phoneme, stress)
        }.joinToString("")
    }

    /**
     * Inserts a stress mark immediately before the first vowel in a phoneme string.
     *
     * @param phoneme IPA phoneme for a single letter name
     * @param stress stress mark to insert ("ˈ" for primary, "ˌ" for secondary)
     * @return phoneme string with stress mark inserted
     */
    private fun insertStressBeforeVowel(phoneme: String, stress: String): String {
        val vowelIdx = phoneme.indexOfFirst { it in VOWEL_CHARS }
        return if (vowelIdx >= 0) {
            StringBuilder(phoneme.length + stress.length).apply {
                append(phoneme, 0, vowelIdx)
                append(stress)
                append(phoneme, vowelIdx, phoneme.length)
            }.toString()
        } else {
            stress + phoneme
        }
    }
}

private val SIMPLE_CONSONANTS =
    mapOf(
        'b' to "b",
        'd' to "d",
        'f' to "f",
        'j' to "ʤ",
        'k' to "k",
        'l' to "l",
        'm' to "m",
        'n' to "n",
        'p' to "p",
        'r' to "ɹ",
        't' to "t",
        'v' to "v",
        'w' to "w",
        'z' to "z",
    )

private val VOWEL_BEFORE_R =
    mapOf(
        'a' to "ɑ",
        'e' to "ə",
        'o' to "ɔ",
        'u' to "ə",
    )

private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')
private val U_LONG_PREV = setOf('d', 'j', 'l', 'n', 'r', 's', 't')
private val SOFT_VOWELS = setOf('e', 'i', 'y')
private val SILENT_H_PREV = setOf('t', 'c', 's', 'g', 'r', 'w')

private const val DOUBLED_CONSONANTS = "bcdfgklmnprstvz"

private val VOWEL_CHARS =
    setOf(
        'ɑ', 'æ', 'ʌ', 'ɔ', 'ə', 'ɛ', 'ɪ', 'i', 'ʊ', 'u', 'ɜ', 'ɐ', 'ᵻ',
        // Misaki diphthong symbols
        'I', 'W', 'A', 'O', 'Y',
    )

private val LETTER_NAMES =
    mapOf(
        'a' to "A", 'b' to "bi", 'c' to "si", 'd' to "di", 'e' to "i",
        'f' to "ɛf", 'g' to "ʤi", 'h' to "Aʧ", 'i' to "I", 'j' to "ʤA",
        'k' to "kA", 'l' to "ɛl", 'm' to "ɛm", 'n' to "ɛn", 'o' to "O",
        'p' to "pi", 'q' to "kju", 'r' to "ɑɹ", 's' to "ɛs", 't' to "ti",
        'u' to "ju", 'v' to "vi", 'w' to "dʌbəlju", 'x' to "ɛks", 'y' to "wI",
        'z' to "zi",
    )

private val DIGIT_NAMES =
    mapOf(
        '0' to "zˈiɹO", '1' to "wˈʌn", '2' to "tˈu", '3' to "θɹˈi",
        '4' to "fˈɔɹ", '5' to "fˈIv", '6' to "sˈɪks", '7' to "sˈɛvən",
        '8' to "ˈAt", '9' to "nˈIn",
    )
