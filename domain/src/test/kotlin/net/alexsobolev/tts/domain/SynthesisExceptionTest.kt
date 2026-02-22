package net.alexsobolev.tts.domain

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs

class SynthesisExceptionTest {
    @Test
    fun `voice not found message`() {
        // when
        val ex = SynthesisException.VoiceNotFound("af_missing")

        // then
        assertContains(ex.message, "af_missing")
    }

    @Test
    fun `text too long message`() {
        // when
        val ex = SynthesisException.TextTooLong(6000, 5000)

        // then
        assertContains(ex.message, "6000")
        assertContains(ex.message, "5000")
    }

    @Test
    fun `speed out of range message`() {
        // when
        val ex = SynthesisException.SpeedOutOfRange(3.0)

        // then
        assertContains(ex.message, "3.0")
    }

    @Test
    fun `inference failed message`() {
        // when
        val ex = SynthesisException.InferenceFailed("oom")

        // then
        assertContains(ex.message, "oom")
    }

    @Test
    fun `storage failed message`() {
        // when
        val ex = SynthesisException.StorageFailed("timeout")

        // then
        assertContains(ex.message, "timeout")
    }

    @Test
    fun `dialogue empty message`() {
        // when
        val ex = SynthesisException.DialogueEmpty()

        // then
        assertContains(ex.message, "at least one turn")
    }

    @Test
    fun `invalid input message`() {
        // when
        val ex = SynthesisException.InvalidInput("bad blend weights")

        // then
        assertContains(ex.message, "bad blend weights")
        assertContains(ex.message, "Invalid input")
    }

    @Test
    fun `all are exceptions`() {
        // given
        val exceptions: List<SynthesisException> =
            listOf(
                SynthesisException.VoiceNotFound("x"),
                SynthesisException.TextTooLong(1, 2),
                SynthesisException.SpeedOutOfRange(0.0),
                SynthesisException.InferenceFailed("x"),
                SynthesisException.StorageFailed("x"),
                SynthesisException.DialogueEmpty(),
                SynthesisException.InvalidInput("test"),
            )

        // then
        for (ex in exceptions) {
            assertIs<Exception>(ex)
        }
    }
}
