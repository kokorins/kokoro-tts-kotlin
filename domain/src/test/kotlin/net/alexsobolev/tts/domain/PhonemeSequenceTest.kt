package net.alexsobolev.tts.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PhonemeSequenceTest {
    @Test
    fun `valid sequence`() {
        // when
        val seq = PhonemeSequence("hɛˈloʊ")

        // then
        assertEquals("hɛˈloʊ", seq.value)
    }

    @Test
    fun `length returns char count`() {
        // when
        val seq = PhonemeSequence("abc")

        // then
        assertEquals(3, seq.length)
    }

    @Test
    fun `toString hides content`() {
        // when
        val seq = PhonemeSequence("hɛˈloʊ")

        // then
        assertEquals("PhonemeSequence(length=6)", seq.toString())
    }

    @Test
    fun `blank sequence throws`() {
        assertFailsWith<IllegalArgumentException> {
            PhonemeSequence("")
        }
    }

    @Test
    fun `whitespace only sequence throws`() {
        assertFailsWith<IllegalArgumentException> {
            PhonemeSequence("   ")
        }
    }
}
