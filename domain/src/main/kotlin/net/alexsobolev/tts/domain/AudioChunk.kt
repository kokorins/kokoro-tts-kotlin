package net.alexsobolev.tts.domain

/**
 * Encoded audio bytes together with the format they were encoded in.
 *
 * @param data the raw encoded audio bytes
 * @param format the audio format describing how the bytes are encoded
 */
data class AudioChunk(val data: ByteArray, val format: AudioFormat) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioChunk

        if (!data.contentEquals(other.data)) return false
        if (format != other.format) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + format.hashCode()
        return result
    }
}
