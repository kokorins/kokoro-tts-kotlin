package net.alexsobolev.tts.infra.g2p

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnglishPhonemeGeneratorTest {
    private val generator =
        EnglishPhonemeGenerator(
            lexicon = PosAwareLexicon(
                "../data/us_gold.json",
                "../data/us_silver.json",
                "../data/gb_gold.json",
                "../data/gb_silver.json",
                "../data/lexicon_fixes.json",
            ),
            fallback = LetterPhonemeConverter(LetterPhonemeRules()),
            numberExpander = NumberExpander(),
            posTagger = OpenNlpPosTagger("../data/en-pos-perceptron.bin"),
        )

    @Test
    fun `single word annotation`() = runTest {
        // when
        val result = generator.generate("(hello)[həˈloʊ]")

        // then
        assertEquals("həˈloʊ", result.value)
    }

    @Test
    fun `multi word annotation`() = runTest {
        // when
        val result = generator.generate("(Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː]")

        // then
        assertEquals("mˈɑːtʃuː pˈiːtʃuː", result.value)
    }

    @Test
    fun `annotation mixed with normal text`() = runTest {
        // when
        val result = generator.generate("visit (Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː] today")

        // then
        assertEquals("vˈɪzət mˈɑːtʃuː pˈiːtʃuː tədˈA", result.value)
    }

    @Test
    fun `annotation with trailing punctuation`() = runTest {
        // when
        val result = generator.generate("(Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː].")

        // then
        assertEquals("mˈɑːtʃuː pˈiːtʃuː.", result.value)
    }

    @Test
    fun `multiple annotations in one sentence`() = runTest {
        // when
        val result =
            generator.generate(
                "(Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː] and (Huascaran)[wɑːskɑːɹˈɑːn]",
            )

        // then
        assertEquals("mˈɑːtʃuː pˈiːtʃuː ænd wɑːskɑːɹˈɑːn", result.value)
    }

    @Test
    fun `annotation syllable dots stripped`() = runTest {
        // when
        val result = generator.generate("(Pachacamac)[pa.tʃa.ˈka.mak]")

        // then
        assertEquals("patʃaˈkamak", result.value)
    }

    @Test
    fun `voice blending sentence`() = runTest {
        // when
        val result =
            generator.generate(
                "it extracts the corresponding row from each voice, multiplies each by its weight, and sums them element by element.",
            )

        // then
        assertEquals(
            "ɪt ɪkstɹˈækts ðə kˌɔɹəspˈɑndɪŋ ɹˈO fɹʌm ˈiʧ vˈYs," +
                " mˈʌltəplˌIz ˈiʧ bI ɪts wˈAt, ænd sˈʌmz ðˌɛm ˈɛləmənt bI ˈɛləmənt.",
            result.value,
        )
    }

    @Test
    fun `abbreviation converted`() = runTest {
        // when
        val result = generator.generate("NASA")

        // then
        assertTrue(result.value.isNotBlank())
    }

    @Test
    fun `contraction expanded`() = runTest {
        // when
        val result = generator.generate("I'm happy")

        // then
        assertTrue(result.value.isNotBlank())
        assertTrue(result.value.contains("hˈæpi"))
    }

    @Test
    fun `past tense inflection`() = runTest {
        // when
        val result = generator.generate("averaged")

        // then
        assertTrue(result.value.isNotBlank())
    }

    @Test
    fun `progressive inflection`() = runTest {
        // when
        val result = generator.generate("running")

        // then
        assertTrue(result.value.isNotBlank())
    }

    @Test
    fun `adverb derivation`() = runTest {
        // when
        val result = generator.generate("quickly")

        // then
        assertTrue(result.value.isNotBlank())
    }

    @Test
    fun `number expansion`() = runTest {
        // when
        val result = generator.generate("42")

        // then
        assertTrue(result.value.isNotBlank())
    }

    @Test
    fun `the before vowel`() = runTest {
        // when
        val result = generator.generate("the apple")

        // then
        assertTrue(result.value.contains("ði"))
    }

    @Test
    fun `the before consonant`() = runTest {
        // when
        val result = generator.generate("the book")

        // then
        assertTrue(result.value.contains("ðə"))
    }

    @Test
    fun `compound word`() = runTest {
        // when
        val result = generator.generate("something")

        // then
        assertTrue(result.value.isNotBlank())
    }

    @Test
    fun `plural word`() = runTest {
        // when
        val result = generator.generate("churches")

        // then
        assertTrue(result.value.isNotBlank())
    }

    @Test
    fun `possessive today's in sentence`() = runTest {
        // when
        val result = generator.generate("Today's weather is absolutely beautiful.")

        // then
        assertEquals("tədˈAz wˈɛðəɹ ɪz ˈæbsəlˌutli bjˈuɾəfəl.", result.value)
    }

    @Test
    fun `simple statement`() = runTest {
        // when
        val result = generator.generate("People still live there.")

        // then
        assertEquals("pˈipᵊl stˈɪl lˈɪv ðˈɛɹ.", result.value)
    }

    @Test
    fun `read present tense`() = runTest {
        // when
        val result = generator.generate("I read books every day.")

        // then — present "read" should be ɹˈid (not past tense ɹˈɛd)
        assertTrue(result.value.contains("ɹˈid"), "Expected present tense ɹˈid, got: ${result.value}")
    }

    @Test
    fun `read past tense`() = runTest {
        // when
        val result = generator.generate("Yesterday I read the entire novel.")

        // then — past "read" should be ɹˈɛd
        assertTrue(result.value.contains("ɹˈɛd"), "Expected past tense ɹˈɛd, got: ${result.value}")
    }

    @Test
    fun `agent noun derivation`() = runTest {
        // when
        val result = generator.generate("teacher")

        // then
        assertTrue(result.value.isNotBlank())
    }
}
