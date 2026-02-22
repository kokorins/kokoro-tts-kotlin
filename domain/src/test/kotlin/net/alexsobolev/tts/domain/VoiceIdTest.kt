package net.alexsobolev.tts.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VoiceIdTest {
    @Test
    fun `valid voice id`() {
        // when
        val id = VoiceId("af_heart")

        // then
        assertEquals("af_heart", id.value)
    }

    @Test
    fun `blank voice id throws`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceId("")
        }
    }

    @Test
    fun `whitespace only voice id throws`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceId("   ")
        }
    }
}
