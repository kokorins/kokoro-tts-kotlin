package net.alexsobolev.tts.infra.storage

import net.alexsobolev.tts.core.api.AudioStorage
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.StoredAudio
import net.alexsobolev.tts.domain.VoiceSpec
import java.io.File
import java.util.UUID

/**
 * Stores encoded audio on the local filesystem and returns a URL
 * served by Ktor's static file route.
 */
internal class LocalFileAudioStorage(private val outputDir: String, private val baseUrl: String) : AudioStorage {
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
        val fileName = "${UUID.randomUUID()}.$formatExtension"
        val dir = File(outputDir, voiceName)
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeBytes(audio)

        val key = "$voiceName/$fileName"
        val url = "${baseUrl.trimEnd('/')}/audio/$key"

        return StoredAudio(
            url = url,
            key = key,
            expiresInSeconds = 0,
            sizeBytes = audio.size.toLong(),
        )
    }
}
