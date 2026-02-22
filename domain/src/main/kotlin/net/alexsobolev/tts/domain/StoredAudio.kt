package net.alexsobolev.tts.domain

/**
 * Metadata returned after audio is persisted to remote storage.
 *
 * @param url the download URL for the stored audio
 * @param key the storage object key
 * @param expiresInSeconds seconds until the download URL expires
 * @param sizeBytes size of the stored audio file in bytes
 */
data class StoredAudio(
    val url: String,
    val key: String,
    val expiresInSeconds: Long,
    val sizeBytes: Long,
)
