package net.alexsobolev.tts.core.api

import net.alexsobolev.tts.domain.VoiceId
import net.alexsobolev.tts.domain.VoiceProfile

/**
 * Provides access to available voice profiles and their embedding vectors.
 */
interface VoiceRepository {
    /**
     * Find a voice profile by its unique identifier.
     *
     * @param id the voice identifier to look up
     * @return the matching [VoiceProfile], or null if not found
     */
    suspend fun findById(id: VoiceId): VoiceProfile?

    /**
     * Retrieve all available voice profiles.
     *
     * @return list of all voice profiles in the repository
     */
    suspend fun findAll(): List<VoiceProfile>
}
