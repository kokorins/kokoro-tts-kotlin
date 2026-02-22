package net.alexsobolev.tts.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpeechRateTest {
    @Test
    fun `valid speed`() {
        // when
        val rate = SpeechRate(1.0)

        // then
        assertEquals(1.0, rate.value)
    }

    @Test
    fun `minimum speed`() {
        // when
        val rate = SpeechRate(Constants.MIN_SPEED)

        // then
        assertEquals(0.5, rate.value)
    }

    @Test
    fun `maximum speed`() {
        // when
        val rate = SpeechRate(Constants.MAX_SPEED)

        // then
        assertEquals(2.0, rate.value)
    }

    @Test
    fun `below minimum throws`() {
        assertFailsWith<IllegalArgumentException> {
            SpeechRate(0.4)
        }
    }

    @Test
    fun `above maximum throws`() {
        assertFailsWith<IllegalArgumentException> {
            SpeechRate(2.1)
        }
    }
}
