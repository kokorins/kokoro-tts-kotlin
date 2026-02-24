package net.alexsobolev.tts.infra.storage

import kotlinx.coroutines.test.runTest
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.SampleRate
import net.alexsobolev.tts.domain.VoiceBlendWeight
import net.alexsobolev.tts.domain.VoiceId
import net.alexsobolev.tts.domain.VoiceSpec
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalFileAudioStorageTest {
    private val outputDir = File(System.getProperty("java.io.tmpdir"), "local-storage-test-${System.nanoTime()}")
    private val baseUrl = "http://localhost:8080"
    private val storage = LocalFileAudioStorage(outputDir.absolutePath, baseUrl)

    @AfterTest
    fun cleanup() {
        outputDir.deleteRecursively()
    }

    @Test
    fun `store WAV with single voice creates file`() = runTest {
        // when
        val result =
            storage.store(
                byteArrayOf(1, 2, 3),
                AudioFormat.WAV(SampleRate(24000)),
                VoiceSpec.SingleVoice(VoiceId("af_heart")),
            )

        // then
        assertEquals(0L, result.expiresInSeconds)
        assertEquals(3L, result.sizeBytes)
        assertTrue(result.key.startsWith("af_heart/"))
        assertTrue(result.key.endsWith(".wav"))
        assertTrue(result.url.startsWith("http://localhost:8080/audio/af_heart/"))
        assertTrue(result.url.endsWith(".wav"))

        val file = File(outputDir, result.key)
        assertTrue(file.exists())
        assertEquals(3, file.readBytes().size)
    }

    @Test
    fun `store MP3 with single voice`() = runTest {
        // when
        val result =
            storage.store(
                byteArrayOf(1, 2),
                AudioFormat.MP3(SampleRate(24000)),
                VoiceSpec.SingleVoice(VoiceId("am_adam")),
            )

        // then
        assertTrue(result.key.startsWith("am_adam/"))
        assertTrue(result.key.endsWith(".mp3"))
        assertTrue(result.url.endsWith(".mp3"))

        val file = File(outputDir, result.key)
        assertTrue(file.exists())
    }

    @Test
    fun `store with blended voice uses blended prefix`() = runTest {
        // when
        val result =
            storage.store(
                byteArrayOf(1),
                AudioFormat.WAV(SampleRate(24000)),
                VoiceSpec.BlendedVoice(
                    listOf(VoiceId("af_heart") to VoiceBlendWeight(0.6f), VoiceId("af_star") to VoiceBlendWeight(0.4f)),
                ),
            )

        // then
        assertTrue(result.key.startsWith("blended/"))
        assertTrue(File(outputDir, result.key).exists())
    }

    @Test
    fun `url uses configured base url`() = runTest {
        val customStorage = LocalFileAudioStorage(outputDir.absolutePath, "https://example.com:9090")

        // when
        val result =
            customStorage.store(
                byteArrayOf(1),
                AudioFormat.WAV(SampleRate(24000)),
                VoiceSpec.SingleVoice(VoiceId("af_heart")),
            )

        // then
        assertTrue(result.url.startsWith("https://example.com:9090/audio/"))
    }
}
