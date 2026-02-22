package net.alexsobolev.tts.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SampleRateTest {
    @Test
    fun `all valid rates`() {
        // given
        val valid = listOf(8000, 16000, 22050, 24000, 44100, 48000)

        // then
        for (hz in valid) {
            assertEquals(hz, SampleRate(hz).hz)
        }
    }

    @Test
    fun `invalid rate throws`() {
        assertFailsWith<IllegalArgumentException> {
            SampleRate(11025)
        }
    }

    @Test
    fun `zero rate throws`() {
        assertFailsWith<IllegalArgumentException> {
            SampleRate(0)
        }
    }
}
