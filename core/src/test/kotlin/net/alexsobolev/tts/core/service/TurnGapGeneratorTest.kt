package net.alexsobolev.tts.core.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TurnGapGeneratorTest {
    @Test
    fun `generate produces silence`() {
        // given
        val generator = TurnGapGenerator(sampleRate = 24000, random = Random(42))

        // when
        val gap = generator.generate(1.0)

        // then
        assertTrue(gap.all { it == 0.0f })
    }

    @Test
    fun `sample count matches expected duration`() {
        // given
        // Fixed random always returns minGapMs (250) for seed that gives 250
        val generator =
            TurnGapGenerator(
                sampleRate = 24000,
                minGapMs = 300,
                maxGapMs = 300,
                random = Random(0),
            )

        // when
        val gap = generator.generate(1.0)

        // then
        // 24000 * 300 / 1000 = 7200
        assertEquals(7200, gap.size)
    }

    @Test
    fun `faster speed produces shorter gap`() {
        // given
        val generator =
            TurnGapGenerator(
                sampleRate = 24000,
                minGapMs = 400,
                maxGapMs = 400,
                random = Random(0),
            )

        // when
        val normalGap = generator.generate(1.0)
        val fastGap = generator.generate(2.0)

        // then
        assertTrue(fastGap.size < normalGap.size)
    }

    @Test
    fun `slower speed produces longer gap`() {
        // given
        val generator =
            TurnGapGenerator(
                sampleRate = 24000,
                minGapMs = 400,
                maxGapMs = 400,
                random = Random(0),
            )

        // when
        val normalGap = generator.generate(1.0)
        val slowGap = generator.generate(0.5)

        // then
        assertTrue(slowGap.size > normalGap.size)
    }

    @Test
    fun `custom sample rate`() {
        // given
        val generator =
            TurnGapGenerator(
                sampleRate = 16000,
                minGapMs = 500,
                maxGapMs = 500,
                random = Random(0),
            )

        // when
        val gap = generator.generate(1.0)

        // then
        // 16000 * 500 / 1000 = 8000
        assertEquals(8000, gap.size)
    }

    @Test
    fun `gap size within expected range`() {
        // given
        val generator =
            TurnGapGenerator(
                sampleRate = 24000,
                minGapMs = 250,
                maxGapMs = 500,
                random = Random(99),
            )

        // when
        val gap = generator.generate(1.0)

        // then
        // min: 24000 * 250 / 1000 = 6000
        // max: 24000 * 500 / 1000 = 12000
        assertTrue(gap.size in 6000..12000)
    }
}
