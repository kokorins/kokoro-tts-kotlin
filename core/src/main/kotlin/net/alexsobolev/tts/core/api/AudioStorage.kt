package net.alexsobolev.tts.core.api

import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.StoredAudio
import net.alexsobolev.tts.domain.VoiceSpec

/**
 * Persists encoded audio bytes and returns download metadata.
 *
 * This contract separates the synthesis pipeline from storage
 * concerns. The domain produces audio bytes; what happens to
 * those bytes afterward (S3, local disk, CDN) is an
 * implementation detail.
 *
 * Thread safety: implementations must be safe to call from
 * multiple coroutines concurrently.
 */
interface AudioStorage {
    /**
     * Upload encoded audio and return storage metadata.
     *
     * @param audio the encoded audio bytes (WAV or MP3)
     * @param format the audio format, used to set content type
     *        and file extension
     * @param voice the voice spec, used to organize storage
     *        paths (e.g. per voice subdirectories)
     * @return metadata including a presigned download URL,
     *         storage key, expiration, and byte size
     * @throws RuntimeException if the upload fails
     */
    suspend fun store(audio: ByteArray, format: AudioFormat, voice: VoiceSpec): StoredAudio
}
