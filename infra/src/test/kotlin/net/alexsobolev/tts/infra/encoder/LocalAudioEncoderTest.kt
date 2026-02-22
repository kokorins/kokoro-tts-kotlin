package net.alexsobolev.tts.infra.encoder

import kotlinx.coroutines.test.runTest
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.SampleRate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalAudioEncoderTest {
    private val encoder = LocalAudioEncoder()
    private val wavFormat = AudioFormat.WAV(SampleRate(24000))
    private val mp3Format = AudioFormat.MP3(SampleRate(24000))

    @Test
    fun `WAV header starts with RIFF`() = runTest {
        // given
        val samples = floatArrayOf(0.0f, 0.5f, -0.5f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        val header = String(chunk.data, 0, 4)
        assertEquals("RIFF", header)
    }

    @Test
    fun `WAV header contains WAVE`() = runTest {
        // given
        val samples = floatArrayOf(0.0f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        val wave = String(chunk.data, 8, 4)
        assertEquals("WAVE", wave)
    }

    @Test
    fun `WAV header is 44 bytes`() = runTest {
        // given
        val samples = floatArrayOf(0.1f, 0.2f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        // Header 44 bytes + 2 samples * 2 bytes = 48
        assertEquals(48, chunk.data.size)
    }

    @Test
    fun `WAV format is PCM 16 mono`() = runTest {
        // given
        val samples = floatArrayOf(0.0f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        val buf = ByteBuffer.wrap(chunk.data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(20) // fmt chunk: format tag
        assertEquals(1.toShort(), buf.short) // PCM
        assertEquals(1.toShort(), buf.short) // mono
        assertEquals(24000, buf.int) // sample rate
    }

    @Test
    fun `WAV sample rate in header`() = runTest {
        // given
        val format = AudioFormat.WAV(SampleRate(44100))
        val samples = floatArrayOf(0.0f)

        // when
        val chunk = encoder.encode(samples, format)

        // then
        val buf = ByteBuffer.wrap(chunk.data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(24) // sample rate offset
        assertEquals(44100, buf.int)
    }

    @Test
    fun `WAV PCM conversion zero`() = runTest {
        // given
        val samples = floatArrayOf(0.0f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        val buf = ByteBuffer.wrap(chunk.data, 44, 2).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0.toShort(), buf.short)
    }

    @Test
    fun `WAV PCM conversion positive`() = runTest {
        // given
        val samples = floatArrayOf(1.0f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        val buf = ByteBuffer.wrap(chunk.data, 44, 2).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Short.MAX_VALUE, buf.short)
    }

    @Test
    fun `WAV PCM conversion negative`() = runTest {
        // given
        val samples = floatArrayOf(-1.0f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        val buf = ByteBuffer.wrap(chunk.data, 44, 2).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((-32767).toShort(), buf.short)
    }

    @Test
    fun `WAV clamps beyond positive one`() = runTest {
        // given
        val samples = floatArrayOf(2.0f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        val buf = ByteBuffer.wrap(chunk.data, 44, 2).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Short.MAX_VALUE, buf.short)
    }

    @Test
    fun `WAV clamps beyond negative one`() = runTest {
        // given
        val samples = floatArrayOf(-2.0f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        val buf = ByteBuffer.wrap(chunk.data, 44, 2).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals((-32767).toShort(), buf.short)
    }

    @Test
    fun `WAV format preserved`() = runTest {
        // given
        val samples = floatArrayOf(0.0f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        assertEquals(wavFormat, chunk.format)
    }

    @Test
    fun `MP3 produces non empty output`() = runTest {
        // given
        // Generate enough samples for MP3 encoder to produce output
        val samples = FloatArray(24000) { kotlin.math.sin(it * 0.1).toFloat() }

        // when
        val chunk = encoder.encode(samples, mp3Format)

        // then
        assertTrue(chunk.data.isNotEmpty())
    }

    @Test
    fun `MP3 format preserved`() = runTest {
        // given
        val samples = FloatArray(24000) { 0.0f }

        // when
        val chunk = encoder.encode(samples, mp3Format)

        // then
        assertEquals(mp3Format, chunk.format)
    }

    @Test
    fun `WAV file size in header`() = runTest {
        // given
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        val buf = ByteBuffer.wrap(chunk.data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        val fileSize = buf.int
        // fileSize = 36 + dataSize, total = 44 + 6 = 50
        assertEquals(chunk.data.size - 8, fileSize)
    }

    @Test
    fun `WAV data chunk size in header`() = runTest {
        // given
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f)

        // when
        val chunk = encoder.encode(samples, wavFormat)

        // then
        val buf = ByteBuffer.wrap(chunk.data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(40) // data chunk size at offset 40
        val dataSize = buf.int
        assertEquals(samples.size * 2, dataSize)
    }

    @Test
    fun `empty samples produces header only`() = runTest {
        // when
        val chunk = encoder.encode(floatArrayOf(), wavFormat)

        // then
        assertEquals(44, chunk.data.size)
    }
}
