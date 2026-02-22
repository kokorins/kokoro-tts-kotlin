package net.alexsobolev.tts.infra.storage

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import net.alexsobolev.tts.core.api.AudioStorage
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.StoredAudio
import net.alexsobolev.tts.domain.VoiceSpec
import java.util.UUID

/**
 * Stores encoded audio in Amazon S3 and returns a public URL for download.
 */
internal class S3AudioStorage(
    private val s3: S3Client,
    private val bucketName: String,
    private val region: String,
    private val storagePrefix: String,
) : AudioStorage {
    override suspend fun store(audio: ByteArray, format: AudioFormat, voice: VoiceSpec): StoredAudio {
        val voiceName =
            when (voice) {
                is VoiceSpec.SingleVoice -> voice.voiceId.value
                is VoiceSpec.BlendedVoice -> "blended"
            }
        val formatExtension =
            when (format) {
                is AudioFormat.WAV -> "wav"
                is AudioFormat.MP3 -> "mp3"
            }
        val objectKey = "$storagePrefix/$voiceName/${UUID.randomUUID()}.$formatExtension"
        val contentType =
            when (format) {
                is AudioFormat.MP3 -> "audio/mpeg"
                is AudioFormat.WAV -> "audio/wav"
            }

        s3.putObject(
            PutObjectRequest {
                bucket = bucketName
                key = objectKey
                this.contentType = contentType
                body = ByteStream.fromBytes(audio)
            },
        )

        val publicUrl = "https://$bucketName.s3.$region.amazonaws.com/$objectKey"

        return StoredAudio(
            url = publicUrl,
            key = objectKey,
            expiresInSeconds = 0,
            sizeBytes = audio.size.toLong(),
        )
    }
}
