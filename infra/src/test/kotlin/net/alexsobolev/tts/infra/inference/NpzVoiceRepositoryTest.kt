package net.alexsobolev.tts.infra.inference

import kotlinx.coroutines.test.runTest
import net.alexsobolev.tts.domain.VoiceId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NpzVoiceRepositoryTest {
    private lateinit var npzFile: File

    @BeforeTest
    fun setup() {
        npzFile =
            createTestNpzFile(
                mapOf(
                    "af_heart" to FloatArray(EMBEDDING_SIZE) { it.toFloat() },
                    "bf_emma" to FloatArray(EMBEDDING_SIZE) { it.toFloat() * 2 },
                    "jf_alpha" to FloatArray(EMBEDDING_SIZE) { 0.5f },
                ),
            )
    }

    @AfterTest
    fun teardown() {
        npzFile.delete()
    }

    @Test
    fun `find by id returns existing voice`() = runTest {
        // given
        val repo = NpzVoiceRepository(npzFile.absolutePath)

        // when
        val profile = repo.findById(VoiceId("af_heart"))

        // then
        assertNotNull(profile)
        assertEquals("af_heart", profile.id.value)
        assertEquals("en-US", profile.language)
        assertEquals(EMBEDDING_SIZE, profile.embedding.size)
    }

    @Test
    fun `find by id returns null for missing voice`() = runTest {
        // given
        val repo = NpzVoiceRepository(npzFile.absolutePath)

        // when
        val profile = repo.findById(VoiceId("nonexistent"))

        // then
        assertNull(profile)
    }

    @Test
    fun `find by id caches result`() = runTest {
        // given
        val repo = NpzVoiceRepository(npzFile.absolutePath)

        // when
        val first = repo.findById(VoiceId("af_heart"))
        val second = repo.findById(VoiceId("af_heart"))

        // then
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first === second)
    }

    @Test
    fun `find all returns all voices`() = runTest {
        // given
        val repo = NpzVoiceRepository(npzFile.absolutePath)

        // when
        val profiles = repo.findAll()

        // then
        assertEquals(3, profiles.size)
        val ids = profiles.map { it.id.value }.toSet()
        assertTrue("af_heart" in ids)
        assertTrue("bf_emma" in ids)
        assertTrue("jf_alpha" in ids)
    }

    @Test
    fun `find by id infers en-US for a prefix`() = runTest {
        // given
        val repo = NpzVoiceRepository(npzFile.absolutePath)

        // when
        val profile = repo.findById(VoiceId("af_heart"))

        // then
        assertEquals("en-US", profile?.language)
    }

    @Test
    fun `find by id infers en-GB for b prefix`() = runTest {
        // given
        val repo = NpzVoiceRepository(npzFile.absolutePath)

        // when
        val profile = repo.findById(VoiceId("bf_emma"))

        // then
        assertEquals("en-GB", profile?.language)
    }

    @Test
    fun `find by id infers ja-JP for j prefix`() = runTest {
        // given
        val repo = NpzVoiceRepository(npzFile.absolutePath)

        // when
        val profile = repo.findById(VoiceId("jf_alpha"))

        // then
        assertEquals("ja-JP", profile?.language)
    }

    @Test
    fun `find by id infers languages from prefix`() = runTest {
        // given
        val languages =
            mapOf(
                "zf_test" to "zh-CN",
                "ff_test" to "fr-FR",
                "hf_test" to "hi-IN",
                "if_test" to "it-IT",
                "pf_test" to "pt-BR",
                "ef_test" to "es-ES",
                "xf_test" to "en-US",
            )
        val voices = languages.keys.associateWith { FloatArray(EMBEDDING_SIZE) { 0.1f } }
        val file = createTestNpzFile(voices)

        try {
            val repo = NpzVoiceRepository(file.absolutePath)

            for ((voiceName, expectedLang) in languages) {
                val profile = repo.findById(VoiceId(voiceName))
                assertNotNull(profile, "Expected profile for $voiceName")
                assertEquals(expectedLang, profile.language, "Language mismatch for $voiceName")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parse NPY version one`() = runTest {
        // given
        val repo = NpzVoiceRepository(npzFile.absolutePath)

        // when
        val profile = repo.findById(VoiceId("af_heart"))

        // then
        assertNotNull(profile)
        assertEquals(0.0f, profile.embedding[0])
        assertEquals(1.0f, profile.embedding[1])
    }

    @Test
    fun `embedding data is correct`() = runTest {
        // given
        val repo = NpzVoiceRepository(npzFile.absolutePath)

        // when
        val profile = repo.findById(VoiceId("bf_emma"))

        // then
        assertNotNull(profile)
        assertEquals(0.0f, profile.embedding[0])
        assertEquals(2.0f, profile.embedding[1])
    }
}

private const val EMBEDDING_SIZE = 510 * 256

private fun createNpyBytes(floats: FloatArray): ByteArray {
    val header = "{'descr': '<f4', 'fortran_order': False, 'shape': (${floats.size},), }"
    val padTarget = 64 - 10
    val padLen = padTarget - header.length - 1
    val paddedHeader = header + " ".repeat(padLen.coerceAtLeast(0)) + "\n"
    val headerBytes = paddedHeader.toByteArray(Charsets.US_ASCII)

    val out = ByteArrayOutputStream()
    out.write(
        byteArrayOf(
            0x93.toByte(),
            'N'.code.toByte(),
            'U'.code.toByte(),
            'M'.code.toByte(),
            'P'.code.toByte(),
            'Y'.code.toByte(),
        ),
    )
    out.write(byteArrayOf(1, 0))
    out.write(
        byteArrayOf(
            (headerBytes.size and 0xFF).toByte(),
            ((headerBytes.size shr 8) and 0xFF).toByte(),
        ),
    )
    out.write(headerBytes)
    val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    buf.asFloatBuffer().put(floats)
    out.write(buf.array())
    return out.toByteArray()
}

private fun createTestNpzFile(voices: Map<String, FloatArray>): File {
    val file = File.createTempFile("test-voices", ".npz")
    ZipOutputStream(FileOutputStream(file)).use { zip ->
        for ((name, floats) in voices) {
            zip.putNextEntry(ZipEntry("$name.npy"))
            zip.write(createNpyBytes(floats))
            zip.closeEntry()
        }
    }
    return file
}
