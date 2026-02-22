package net.alexsobolev.tts.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class VoiceSpecTest {
    @Test
    fun `single voice creation`() {
        // when
        val spec = VoiceSpec.SingleVoice(VoiceId("af_heart"))

        // then
        assertIs<VoiceSpec.SingleVoice>(spec)
        assertEquals("af_heart", spec.voiceId.value)
    }

    @Test
    fun `blended voice with two voices`() {
        // when
        val spec =
            VoiceSpec.BlendedVoice(
                listOf(
                    VoiceId("af_heart") to VoiceBlendWeight(0.6f),
                    VoiceId("af_star") to VoiceBlendWeight(0.4f),
                ),
            )

        // then
        assertEquals(2, spec.voices.size)
    }

    @Test
    fun `blended voice with three voices`() {
        // when
        val spec =
            VoiceSpec.BlendedVoice(
                listOf(
                    VoiceId("a") to VoiceBlendWeight(0.5f),
                    VoiceId("b") to VoiceBlendWeight(0.3f),
                    VoiceId("c") to VoiceBlendWeight(0.2f),
                ),
            )

        // then
        assertEquals(3, spec.voices.size)
    }

    @Test
    fun `blended voice with four voices`() {
        // when
        val spec =
            VoiceSpec.BlendedVoice(
                listOf(
                    VoiceId("a") to VoiceBlendWeight(0.4f),
                    VoiceId("b") to VoiceBlendWeight(0.3f),
                    VoiceId("c") to VoiceBlendWeight(0.2f),
                    VoiceId("d") to VoiceBlendWeight(0.1f),
                ),
            )

        // then
        assertEquals(4, spec.voices.size)
    }

    @Test
    fun `blended voice with five voices throws`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceSpec.BlendedVoice(
                listOf(
                    VoiceId("a") to VoiceBlendWeight(0.3f),
                    VoiceId("b") to VoiceBlendWeight(0.2f),
                    VoiceId("c") to VoiceBlendWeight(0.2f),
                    VoiceId("d") to VoiceBlendWeight(0.2f),
                    VoiceId("e") to VoiceBlendWeight(0.1f),
                ),
            )
        }
    }

    @Test
    fun `blended voice with one voice throws`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceSpec.BlendedVoice(
                listOf(
                    VoiceId("af_heart") to VoiceBlendWeight(1.0f),
                ),
            )
        }
    }

    @Test
    fun `blended voice weight sum too low throws`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceSpec.BlendedVoice(
                listOf(
                    VoiceId("af_heart") to VoiceBlendWeight(0.3f),
                    VoiceId("af_star") to VoiceBlendWeight(0.3f),
                ),
            )
        }
    }

    @Test
    fun `blended voice weight sum too high throws`() {
        assertFailsWith<IllegalArgumentException> {
            VoiceSpec.BlendedVoice(
                listOf(
                    VoiceId("af_heart") to VoiceBlendWeight(0.8f),
                    VoiceId("af_star") to VoiceBlendWeight(0.8f),
                ),
            )
        }
    }

    @Test
    fun `blended voice weight sum tolerance low`() {
        // when (0.99 is within tolerance)
        VoiceSpec.BlendedVoice(
            listOf(
                VoiceId("af_heart") to VoiceBlendWeight(0.5f),
                VoiceId("af_star") to VoiceBlendWeight(0.49f),
            ),
        )
    }
}
