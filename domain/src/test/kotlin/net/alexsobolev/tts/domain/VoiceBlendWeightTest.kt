package net.alexsobolev.tts.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VoiceBlendWeightTest {
    @Test
    fun `valid weight`() {
        // when
        val w = VoiceBlendWeight(0.5f)

        // then
        assertEquals(0.5f, w.weight)
    }

    @Test
    fun `zero weight throws`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceBlendWeight(0.0f)
        }
    }

    @Test
    fun `one weight`() {
        // when
        val w = VoiceBlendWeight(1.0f)

        // then
        assertEquals(1.0f, w.weight)
    }

    @Test
    fun `negative weight throws`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceBlendWeight(-0.1f)
        }
    }

    @Test
    fun `above one weight throws`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceBlendWeight(1.1f)
        }
    }
}
