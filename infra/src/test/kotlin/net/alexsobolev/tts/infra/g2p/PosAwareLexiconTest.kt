package net.alexsobolev.tts.infra.g2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PosAwareLexiconTest {
    private val lexicon = PosAwareLexicon(
        "../data/us_gold.json",
        "../data/us_silver.json",
        "../data/gb_gold.json",
        "../data/gb_silver.json",
        "../data/lexicon_fixes.json",
    )

    @Test
    fun `simple word lookup returns DEFAULT`() {
        // when
        val result = lexicon.lookup("hello", null, true)

        // then
        assertNotNull(result)
        assertTrue(result.isNotBlank())
    }

    @Test
    fun `live as VERB returns verb pronunciation`() {
        // when
        val result = lexicon.lookup("live", "VBP", true)

        // then
        assertEquals("lˈɪv", result)
    }

    @Test
    fun `live as adjective returns DEFAULT`() {
        // when
        val result = lexicon.lookup("live", "JJ", true)

        // then
        assertEquals("lˈIv", result)
    }

    @Test
    fun `record as VERB returns verb pronunciation`() {
        // when
        val result = lexicon.lookup("record", "VB", true)

        // then
        assertEquals("ɹəkˈɔɹd", result)
    }

    @Test
    fun `record as NOUN returns DEFAULT pronunciation`() {
        // when
        val result = lexicon.lookup("record", "NN", true)

        // then
        assertEquals("ɹˈɛkəɹd", result)
    }

    @Test
    fun `parent tag normalization VBD to VERB`() {
        // when
        val result = lexicon.lookup("read", "VBD", true)

        // then
        assertEquals("ɹˈɛd", result)
    }

    @Test
    fun `parent tag normalization JJ to ADJ`() {
        // when
        val result = lexicon.lookup("read", "JJ", true)

        // then
        assertEquals("ɹˈɛd", result)
    }

    @Test
    fun `None key selected at sentence end`() {
        // when
        val result = lexicon.lookup("there", null, null)

        // then
        assertEquals("ðˈɛɹ", result)
    }

    @Test
    fun `DEFAULT selected when futureVowel is not null`() {
        // when
        val result = lexicon.lookup("there", null, true)

        // then
        assertEquals("ðɛɹ", result)
    }

    @Test
    fun `unknown word returns null`() {
        // when
        val result = lexicon.lookup("xyzzyplugh", null, true)

        // then
        assertNull(result)
    }

    @Test
    fun `case variant capitalized word`() {
        // when
        val result = lexicon.lookup("Hello", null, true)

        // then
        assertNotNull(result)
    }

    @Test
    fun `contains returns true for known word`() {
        assertTrue(lexicon.contains("hello"))
    }

    @Test
    fun `contains returns false for unknown word`() {
        assertTrue(!lexicon.contains("xyzzyplugh"))
    }

    @Test
    fun `read VBP returns present tense after fix`() {
        // when
        val result = lexicon.lookup("read", "VBP", true)

        // then
        assertEquals("ɹˈid", result)
    }
}
