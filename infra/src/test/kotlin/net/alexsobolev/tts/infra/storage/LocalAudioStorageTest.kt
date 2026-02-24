package net.alexsobolev.tts.infra.storage

import kotlinx.coroutines.test.runTest
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.SampleRate
import net.alexsobolev.tts.domain.VoiceBlendWeight
import net.alexsobolev.tts.domain.VoiceId
import net.alexsobolev.tts.domain.VoiceSpec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalAudioStorageTest {
    @Test
    fun `store WAV with single voice`() = runTest {
        val dir = createTempDir("local-storage").apply { deleteOnExit() }
        val storage = LocalAudioStorage(storagePrefix = dir.absolutePath)

        val result =
            storage.store(
                byteArrayOf(1, 2, 3),
                AudioFormat.WAV(SampleRate(24000)),
                VoiceSpec.SingleVoice(VoiceId("af_heart")),
            )

        assertEquals(0L, result.expiresInSeconds)
        assertEquals(3L, result.sizeBytes)
        assertTrue(result.url.startsWith("file:"))
        assertTrue(result.url.contains("af_heart"))
        assertTrue(result.key.contains("af_heart"))
        assertTrue(result.key.endsWith(".wav"))
        val file = File(result.key)
        assertTrue(file.exists())
        assertEquals(3L, file.length())
    }

    @Test
    fun `store MP3 with single voice`() = runTest {
        val dir = createTempDir("local-storage").apply { deleteOnExit() }
        val storage = LocalAudioStorage(storagePrefix = dir.absolutePath)

        val result =
            storage.store(
                byteArrayOf(1, 2),
                AudioFormat.MP3(SampleRate(24000)),
                VoiceSpec.SingleVoice(VoiceId("am_adam")),
            )

        assertTrue(result.key.contains("am_adam"))
        assertTrue(result.key.endsWith(".mp3"))
        val file = File(result.key)
        assertTrue(file.exists())
        assertEquals(2L, file.length())
    }

    @Test
    fun `store with blended voice uses blended prefix`() = runTest {
        val dir = createTempDir("local-storage").apply { deleteOnExit() }
        val storage = LocalAudioStorage(storagePrefix = dir.absolutePath)

        val result =
            storage.store(
                byteArrayOf(1),
                AudioFormat.WAV(SampleRate(24000)),
                VoiceSpec.BlendedVoice(
                    listOf(VoiceId("af_heart") to VoiceBlendWeight(0.6f), VoiceId("af_star") to VoiceBlendWeight(0.4f)),
                ),
            )

        assertTrue(result.key.contains("blended"))
        val file = File(result.key)
        assertTrue(file.exists())
    }

    @Test
    fun `key layout matches S3 structure with relative prefix`() = runTest {
        val dir = createTempDir("local-storage").apply { deleteOnExit() }
        val prefix = "tts-audio"
        val storage = LocalAudioStorage(storagePrefix = prefix)

        val result =
            storage.store(
                byteArrayOf(1),
                AudioFormat.WAV(SampleRate(24000)),
                VoiceSpec.SingleVoice(VoiceId("af_heart")),
            )

        assertTrue(result.key.startsWith("tts-audio/af_heart/"))
        assertTrue(result.key.endsWith(".wav"))
        val file = File(result.key)
        assertTrue(file.exists())
    }
}
