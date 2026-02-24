package net.alexsobolev.tts.infra.storage

import net.alexsobolev.tts.core.api.AudioStorage
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.StoredAudio
import net.alexsobolev.tts.domain.VoiceSpec
import java.io.File
import java.util.UUID

/**
 * Stores encoded audio on local disk under [storagePrefix] with the same key layout as S3.
 * Returns a file:// URL for the written file.
 */
internal class LocalAudioStorage(
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
        val key = "$storagePrefix/$voiceName/${UUID.randomUUID()}.$formatExtension"
        val file = File(key)
        file.parentFile?.mkdirs()
        file.writeBytes(audio)
        val url = file.toURI().toString()
        return StoredAudio(
            url = url,
            key = key,
            expiresInSeconds = 0,
            sizeBytes = audio.size.toLong(),
        )
    }
}
