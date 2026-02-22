package net.alexsobolev.tts.infra.storage

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.PutObjectResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.SampleRate
import net.alexsobolev.tts.domain.VoiceBlendWeight
import net.alexsobolev.tts.domain.VoiceId
import net.alexsobolev.tts.domain.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class S3AudioStorageTest {
    private val s3 = mockk<S3Client>()
    private val storage = S3AudioStorage(s3, "test-bucket", "us-east-1", "tts-audio")
    private val requestSlot = slot<PutObjectRequest>()

    init {
        coEvery { s3.putObject(capture(requestSlot)) } returns PutObjectResponse {}
    }

    @Test
    fun `store WAV with single voice`() = runTest {
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
        assertTrue(result.url.contains("test-bucket"))
        assertTrue(result.url.contains("us-east-1"))
        assertTrue(result.key.startsWith("tts-audio/af_heart/"))
        assertTrue(result.key.endsWith(".wav"))
        assertEquals("test-bucket", requestSlot.captured.bucket)
        assertEquals("audio/wav", requestSlot.captured.contentType)
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
        assertTrue(result.key.startsWith("tts-audio/am_adam/"))
        assertTrue(result.key.endsWith(".mp3"))
        assertEquals("audio/mpeg", requestSlot.captured.contentType)
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
        assertTrue(result.key.startsWith("tts-audio/blended/"))
    }

    @Test
    fun `store calls S3 putObject`() = runTest {
        // when
        storage.store(
            byteArrayOf(1, 2, 3, 4, 5),
            AudioFormat.WAV(SampleRate(24000)),
            VoiceSpec.SingleVoice(VoiceId("af_heart")),
        )

        // then
        coVerify(exactly = 1) { s3.putObject(any<PutObjectRequest>()) }
        assertEquals(5L, requestSlot.captured.body?.contentLength)
    }
}
