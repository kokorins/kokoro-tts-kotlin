package net.alexsobolev.tts.infra.g2p

/**
 * Expands numeric tokens into spoken English words.
 *
 * Handles integers up to 999,999,999 with cardinal expansion,
 * decimal numbers, comma-separated numbers, and leading-zero digit-by-digit fallback.
 */
internal class NumberExpander {
    /**
     * Expands a numeric token to its spoken English form.
     *
     * @param token raw word token (digits, optional commas and single decimal point)
     * @return expanded English words separated by spaces, or null if the token is not a number
     */
    fun expand(token: String): String? {
        if (token.isEmpty()) return null

        val stripped = if (',' in token) token.replace(",", "") else token
        if (stripped.isEmpty()) return null

        // Check for decimal
        val dotIndex = stripped.indexOf('.')
        if (dotIndex >= 0) {
            return expandDecimal(stripped, dotIndex)
        }

        // Pure integer path
        if (!stripped.all { it.isDigit() }) return null
        return expandInteger(stripped)
    }

    private fun expandDecimal(stripped: String, dotIndex: Int): String? {
        val intPart = stripped.substring(0, dotIndex)
        val fracPart = stripped.substring(dotIndex + 1)

        if (intPart.isNotEmpty() && !intPart.all { it.isDigit() }) return null
        if (fracPart.isEmpty() || !fracPart.all { it.isDigit() }) return null

        val intWords =
            if (intPart.isEmpty() || intPart.all { it == '0' }) {
                "zero"
            } else {
                expandInteger(intPart) ?: return null
            }

        // Trailing zeros in fractional part → omit entirely (e.g. "2.0" → "two")
        if (fracPart.all { it == '0' }) return intWords

        val fracWords = digitByDigit(fracPart)
        return "$intWords point $fracWords"
    }

    private fun expandInteger(s: String): String? {
        if (!s.all { it.isDigit() }) return null

        // Single digit
        if (s.length == 1) return DIGIT_WORDS[s[0] - '0']

        // Leading zeros → digit-by-digit
        if (s.startsWith("0")) return digitByDigit(s)

        val n = s.toLongOrNull() ?: return digitByDigit(s)

        // Too large for cardinal → digit-by-digit
        if (n > 999_999_999L) return digitByDigit(s)

        return expandCardinal(n.toInt())
    }

    private fun expandCardinal(n: Int): String {
        if (n == 0) return "zero"

        val sb = StringBuilder()

        val millions = n / 1_000_000
        if (millions > 0) {
            appendGroup(sb, millions)
            appendWord(sb, "million")
        }

        val thousands = (n % 1_000_000) / 1_000
        if (thousands > 0) {
            appendGroup(sb, thousands)
            appendWord(sb, "thousand")
        }

        val remainder = n % 1_000
        if (remainder > 0) {
            appendGroup(sb, remainder)
        }

        return sb.toString()
    }

    private fun appendWord(sb: StringBuilder, word: String) {
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(word)
    }

    private fun appendGroup(sb: StringBuilder, n: Int) {
        val hundreds = n / 100
        if (hundreds > 0) {
            appendWord(sb, ONES[hundreds])
            appendWord(sb, "hundred")
        }

        val rest = n % 100
        if (rest > 0) {
            when {
                rest < 10 -> appendWord(sb, ONES[rest])
                rest < 20 -> appendWord(sb, TEENS[rest - 10])
                else -> {
                    val tensWord = TENS[rest / 10]
                    val onesWord = ONES[rest % 10]
                    appendWord(sb, if (onesWord.isEmpty()) tensWord else "$tensWord $onesWord")
                }
            }
        }
    }

    private fun digitByDigit(s: String): String = buildString {
        for ((i, ch) in s.withIndex()) {
            if (i > 0) append(' ')
            append(DIGIT_WORDS[ch - '0'])
        }
    }
}

private val ONES = arrayOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
private val TEENS =
    arrayOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen",
    )
private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
private val DIGIT_WORDS = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
