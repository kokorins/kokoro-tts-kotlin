package net.alexsobolev.tts.infra.g2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumberExpanderTest {
    private val expander = NumberExpander()

    // ── Zero and single digits ────────────────────────────────────

    @Test
    fun `zero expands to zero`() {
        // when
        val result = expander.expand("0")

        // then
        assertEquals("zero", result)
    }

    @Test
    fun `single digit five`() {
        // when
        val result = expander.expand("5")

        // then
        assertEquals("five", result)
    }

    @Test
    fun `single digit one`() {
        // when
        val result = expander.expand("1")

        // then
        assertEquals("one", result)
    }

    @Test
    fun `single digit nine`() {
        // when
        val result = expander.expand("9")

        // then
        assertEquals("nine", result)
    }

    // ── Teens ─────────────────────────────────────────────────────

    @Test
    fun `ten`() {
        // when
        val result = expander.expand("10")

        // then
        assertEquals("ten", result)
    }

    @Test
    fun `eleven`() {
        // when
        val result = expander.expand("11")

        // then
        assertEquals("eleven", result)
    }

    @Test
    fun `nineteen`() {
        // when
        val result = expander.expand("19")

        // then
        assertEquals("nineteen", result)
    }

    // ── Tens ──────────────────────────────────────────────────────

    @Test
    fun `twenty`() {
        // when
        val result = expander.expand("20")

        // then
        assertEquals("twenty", result)
    }

    @Test
    fun `forty two`() {
        // when
        val result = expander.expand("42")

        // then
        assertEquals("forty two", result)
    }

    @Test
    fun `ninety`() {
        // when
        val result = expander.expand("90")

        // then
        assertEquals("ninety", result)
    }

    // ── Hundreds ──────────────────────────────────────────────────

    @Test
    fun `one hundred`() {
        // when
        val result = expander.expand("100")

        // then
        assertEquals("one hundred", result)
    }

    @Test
    fun `five hundred ten`() {
        // when
        val result = expander.expand("510")

        // then
        assertEquals("five hundred ten", result)
    }

    @Test
    fun `two hundred fifty six`() {
        // when
        val result = expander.expand("256")

        // then
        assertEquals("two hundred fifty six", result)
    }

    // ── Thousands ─────────────────────────────────────────────────

    @Test
    fun `one thousand`() {
        // when
        val result = expander.expand("1000")

        // then
        assertEquals("one thousand", result)
    }

    @Test
    fun `twenty four thousand`() {
        // when
        val result = expander.expand("24000")

        // then
        assertEquals("twenty four thousand", result)
    }

    @Test
    fun `one thousand twenty four`() {
        // when
        val result = expander.expand("1024")

        // then
        assertEquals("one thousand twenty four", result)
    }

    // ── Millions ──────────────────────────────────────────────────

    @Test
    fun `one million`() {
        // when
        val result = expander.expand("1000000")

        // then
        assertEquals("one million", result)
    }

    @Test
    fun `complex million`() {
        // when
        val result = expander.expand("1234567")

        // then
        assertEquals("one million two hundred thirty four thousand five hundred sixty seven", result)
    }

    // ── Commas ────────────────────────────────────────────────────

    @Test
    fun `comma formatted`() {
        // when
        val result = expander.expand("24,000")

        // then
        assertEquals("twenty four thousand", result)
    }

    @Test
    fun `comma formatted million`() {
        // when
        val result = expander.expand("1,000,000")

        // then
        assertEquals("one million", result)
    }

    // ── Decimals ──────────────────────────────────────────────────

    @Test
    fun `zero point five`() {
        // when
        val result = expander.expand("0.5")

        // then
        assertEquals("zero point five", result)
    }

    @Test
    fun `two point zero drops decimal`() {
        // when
        val result = expander.expand("2.0")

        // then
        assertEquals("two", result)
    }

    @Test
    fun `three point one four`() {
        // when
        val result = expander.expand("3.14")

        // then
        assertEquals("three point one four", result)
    }

    @Test
    fun `trailing double zero dropped`() {
        // when
        val result = expander.expand("1.00")

        // then
        assertEquals("one", result)
    }

    // ── Leading zeros ─────────────────────────────────────────────

    @Test
    fun `leading zeros digit by digit`() {
        // when
        val result = expander.expand("007")

        // then
        assertEquals("zero zero seven", result)
    }

    @Test
    fun `leading zeros longer`() {
        // when
        val result = expander.expand("012")

        // then
        assertEquals("zero one two", result)
    }

    // ── Large numbers → digit-by-digit ────────────────────────────

    @Test
    fun `one billion digit by digit`() {
        // when
        val result = expander.expand("1000000000")

        // then
        assertEquals("one zero zero zero zero zero zero zero zero zero", result)
    }

    // ── Rejection cases ───────────────────────────────────────────

    @Test
    fun `rejects alphabetic word`() {
        assertNull(expander.expand("hello"))
    }

    @Test
    fun `rejects ordinal`() {
        assertNull(expander.expand("3rd"))
    }

    @Test
    fun `rejects empty string`() {
        assertNull(expander.expand(""))
    }

    @Test
    fun `rejects mixed alpha numeric`() {
        assertNull(expander.expand("abc123"))
    }

    @Test
    fun `rejects multiple dots`() {
        assertNull(expander.expand("1.2.3"))
    }
}
