package net.alexsobolev.tts.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AudioChunkTest {
    private val wavFormat = AudioFormat.WAV(SampleRate(24000))

    @Test
    fun `equal chunks`() {
        // given
        val a = AudioChunk(byteArrayOf(1, 2, 3), wavFormat)
        val b = AudioChunk(byteArrayOf(1, 2, 3), wavFormat)

        // then
        assertEquals(a, b)
    }

    @Test
    fun `different data not equal`() {
        // given
        val a = AudioChunk(byteArrayOf(1, 2, 3), wavFormat)
        val b = AudioChunk(byteArrayOf(4, 5, 6), wavFormat)

        // then
        assertNotEquals(a, b)
    }

    @Test
    fun `different format not equal`() {
        // given
        val data = byteArrayOf(1, 2, 3)
        val a = AudioChunk(data, wavFormat)
        val b = AudioChunk(data, AudioFormat.MP3(SampleRate(24000)))

        // then
        assertNotEquals(a, b)
    }

    @Test
    fun `hash code consistent with equals`() {
        // given
        val a = AudioChunk(byteArrayOf(1, 2, 3), wavFormat)
        val b = AudioChunk(byteArrayOf(1, 2, 3), wavFormat)

        // then
        assertEquals(a.hashCode(), b.hashCode())
    }
}
