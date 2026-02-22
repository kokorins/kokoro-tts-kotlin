package net.alexsobolev.tts.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DialogueTurnTest {
    @Test
    fun `valid turn`() {
        // given
        val voice = VoiceSpec.SingleVoice(VoiceId("af_heart"))

        // when
        val turn = DialogueTurn(voice, "Hello world")

        // then
        assertEquals("Hello world", turn.text)
        assertEquals(voice, turn.voice)
    }

    @Test
    fun `blank text throws`() {
        assertFailsWith<IllegalArgumentException> {
            DialogueTurn(
                VoiceSpec.SingleVoice(VoiceId("af_heart")),
                "",
            )
        }
    }

    @Test
    fun `whitespace only text throws`() {
        assertFailsWith<IllegalArgumentException> {
            DialogueTurn(
                VoiceSpec.SingleVoice(VoiceId("af_heart")),
                "   ",
            )
        }
    }
}
