package net.alexsobolev.tts.core.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentencePostProcessorTest {
    private val processor = SentencePostProcessor(sampleRate = 24000)

    @Test
    fun `apply intonation dispatches question mark`() {
        // given
        val samples = FloatArray(12000) { 0.5f }
        val original = samples.copyOf()

        // when
        val result = processor.applyIntonation(samples, "Hello?")

        // then
        assertTrue(!result.contentEquals(original))
    }

    @Test
    fun `apply intonation dispatches exclamation mark`() {
        // given
        val samples = FloatArray(12000) { 0.5f }
        val original = samples.copyOf()

        // when
        val result = processor.applyIntonation(samples, "Wow!")

        // then
        assertTrue(!result.contentEquals(original))
    }

    @Test
    fun `apply intonation returns unmodified for statements`() {
        // given
        val samples = FloatArray(12000) { 0.5f }

        // when
        val result = processor.applyIntonation(samples, "Hello.")

        // then
        assertTrue(result.contentEquals(samples))
    }

    @Test
    fun `apply intonation handles trailing whitespace`() {
        // given
        val samples = FloatArray(12000) { 0.5f }
        val original = samples.copyOf()

        // when
        val result = processor.applyIntonation(samples, "Hello?  ")

        // then
        assertTrue(!result.contentEquals(original))
    }

    @Test
    fun `question intonation preserves length`() {
        // given
        val samples = FloatArray(24000) { 0.5f }

        // when
        val result = processor.applyQuestionIntonation(samples)

        // then
        assertEquals(samples.size, result.size, "Output should be same length as input")
    }

    @Test
    fun `question intonation boosts tail samples`() {
        // given
        val samples = FloatArray(24000) { 0.5f }

        // when
        val result = processor.applyQuestionIntonation(samples)

        // then
        assertTrue(result[result.size - 1] > samples[samples.size - 1], "Tail should be gain-boosted")
    }

    @Test
    fun `question intonation leaves head unchanged`() {
        // given
        val samples = FloatArray(24000) { 0.5f }
        val original = samples.copyOf()

        // when
        val result = processor.applyQuestionIntonation(samples)

        // then
        // Head (before tail region at 600ms = 14400 samples) should be unchanged
        val safeEnd = samples.size - 14400 - 100
        for (i in 0 until safeEnd) {
            assertEquals(original[i], result[i], "Head sample at index $i should be unchanged")
        }
    }

    @Test
    fun `question intonation skips trailing silence`() {
        // given
        // 24000 samples of speech followed by 12000 samples of silence
        val speechLen = 24000
        val samples =
            FloatArray(speechLen + 12000) { i ->
                if (i < speechLen) 0.5f else 0.0f
            }

        // when
        val result = processor.applyQuestionIntonation(samples)

        // then
        assertEquals(samples.size, result.size)
        // Trailing silence should remain zero
        for (i in speechLen until result.size) {
            assertEquals(0.0f, result[i], "Trailing silence at $i should remain silent")
        }
        // Tail of speech should be boosted
        assertTrue(result[speechLen - 1] > 0.5f, "End of speech should be gain-boosted")
    }

    @Test
    fun `exclamation intonation preserves length`() {
        // given
        val samples = FloatArray(24000) { 0.5f }

        // when
        val result = processor.applyExclamationIntonation(samples)

        // then
        assertEquals(samples.size, result.size, "Output should be same length as input")
    }

    @Test
    fun `exclamation intonation boosts start samples`() {
        // given
        val samples = FloatArray(24000) { 0.5f }

        // when
        val result = processor.applyExclamationIntonation(samples)

        // then
        assertTrue(result[0] > samples[0], "First sample should be gain-boosted")
    }

    @Test
    fun `short samples returned unmodified`() {
        // given
        val samples = FloatArray(100) { 0.5f }

        // when
        val questionResult = processor.applyQuestionIntonation(samples)
        val exclamationResult = processor.applyExclamationIntonation(samples)

        // then
        assertTrue(questionResult.contentEquals(samples))
        assertTrue(exclamationResult.contentEquals(samples))
    }

    @Test
    fun `output samples stay in valid range`() {
        // given
        val samples = FloatArray(24000) { 0.95f }

        // when
        val result = processor.applyExclamationIntonation(samples)

        // then
        for (i in result.indices) {
            assertTrue(result[i] <= 1.0f, "Sample at $i exceeds 1.0: ${result[i]}")
            assertTrue(result[i] >= -1.0f, "Sample at $i below -1.0: ${result[i]}")
        }
    }

    @Test
    fun `question intonation with all silence returns same length`() {
        // given
        // All silence -- speechEnd should fall back to samples.size
        val samples = FloatArray(1000) { 0.0f }

        // when
        val result = processor.applyQuestionIntonation(samples)

        // then
        assertEquals(samples.size, result.size)
    }

    @Test
    fun `exclamation intonation with all silence returns same length`() {
        // given
        val samples = FloatArray(1000) { 0.0f }

        // when
        val result = processor.applyExclamationIntonation(samples)

        // then
        assertEquals(samples.size, result.size)
    }

    @Test
    fun `exclamation with leading silence skips silence`() {
        // given
        // 12000 samples of silence followed by 12000 samples of speech
        val samples =
            FloatArray(24000) { i ->
                if (i < 12000) 0.0f else 0.5f
            }

        // when
        val result = processor.applyExclamationIntonation(samples)

        // then
        // Leading silence should remain zero
        for (i in 0 until 12000) {
            assertEquals(0.0f, result[i], "Leading silence at $i should remain silent")
        }
        // First speech sample should be boosted
        assertTrue(result[12000] > 0.5f, "First speech sample should be gain-boosted")
    }
}
