package net.alexsobolev.tts.infra.inference

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.alexsobolev.tts.core.api.VoiceRepository
import net.alexsobolev.tts.core.utils.logger
import net.alexsobolev.tts.domain.VoiceId
import net.alexsobolev.tts.domain.VoiceProfile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Loads voice embedding vectors from a NumPy NPZ archive.
 *
 * Each voice is stored as a .npy entry with shape (510, 1, 256).
 * Profiles are cached in memory after the first load. Language is
 * inferred from the voice ID prefix (e.g. "a" = en-US, "b" = en-GB).
 */
internal class NpzVoiceRepository(private val npzPath: String, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
    VoiceRepository {
    private val logger = logger()
    private val cache = mutableMapOf<VoiceId, VoiceProfile>()
    private val mutex = Mutex()

    override suspend fun findById(id: VoiceId): VoiceProfile? {
        mutex.withLock { cache[id] }?.let { return it }

        return withContext(ioDispatcher) {
            val embedding =
                try {
                    loadVoice(id.value)
                } catch (e: IllegalStateException) {
                    logger.warn("Voice '{}' not found in {}: {}", id.value, npzPath, e.message)
                    return@withContext null
                }

            val profile =
                VoiceProfile(
                    id = id,
                    name = id.value,
                    embedding = embedding,
                    language = inferLanguage(id.value),
                )

            mutex.withLock { cache[id] = profile }
            logger.debug("Cached voice '{}' ({} floats)", id.value, embedding.size)
            profile
        }
    }

    override suspend fun findAll(): List<VoiceProfile> = withContext(ioDispatcher) {
        val names = listVoices()
        logger.debug("Found {} voices in {}", names.size, npzPath)
        coroutineScope {
            names.map { name -> async { findById(VoiceId(name)) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    /**
     * Load a single voice embedding from the NPZ archive.
     *
     * The NPZ file is a standard ZIP where each entry is a .npy
     * file named after the voice (for example, "af_heart.npy").
     * Each .npy contains a header followed by raw float32 data
     * with shape (510, 1, 256). We flatten the middle dimension.
     *
     * @param voiceName the voice identifier (e.g. "af_heart")
     * @return flattened float32 embedding vector
     * @throws IllegalStateException if the voice is not found in the archive
     */
    private fun loadVoice(voiceName: String): FloatArray {
        val zipFile = ZipFile(File(npzPath))

        val entryName = "$voiceName.npy"
        val entry =
            zipFile.getEntry(entryName)
                ?: run {
                    zipFile.close()
                    error(
                        "Voice '$voiceName' not found in $npzPath. " +
                            "Available: ${listVoices()}",
                    )
                }

        val bytes = zipFile.getInputStream(entry).readBytes()
        zipFile.close()

        return parseNpyFloats(bytes)
    }

    /**
     * List all voice names available in the NPZ file.
     *
     * @return sorted list of voice name strings
     */
    private fun listVoices(): List<String> {
        val zipFile = ZipFile(File(npzPath))
        val names =
            zipFile.entries().asSequence()
                .map { it.name.removeSuffix(".npy") }
                .sorted()
                .toList()
        zipFile.close()
        return names
    }

    /**
     * Parse a .npy file's raw bytes into a FloatArray.
     *
     * The NumPy .npy format:
     *   6 bytes: magic "\x93NUMPY"
     *   2 bytes: major version, minor version
     *   2 bytes (v1) or 4 bytes (v2): header length
     *   N bytes: ASCII header string
     *   remaining: raw float32 data (little endian)
     *
     * @param npyBytes raw bytes of the .npy file
     * @return parsed float32 values from the data section
     */
    private fun parseNpyFloats(npyBytes: ByteArray): FloatArray {
        val magic = npyBytes.sliceArray(0 until 6)
        check(magic[0] == 0x93.toByte() && String(magic, 1, 5) == "NUMPY") {
            "Not a valid .npy file"
        }

        val majorVersion = npyBytes[6].toInt() and 0xFF

        val dataOffset: Int
        if (majorVersion == 1) {
            val headerLen =
                (npyBytes[8].toInt() and 0xFF) or
                    ((npyBytes[9].toInt() and 0xFF) shl 8)
            dataOffset = 10 + headerLen
        } else {
            val headerLen =
                (npyBytes[8].toInt() and 0xFF) or
                    ((npyBytes[9].toInt() and 0xFF) shl 8) or
                    ((npyBytes[10].toInt() and 0xFF) shl 16) or
                    ((npyBytes[11].toInt() and 0xFF) shl 24)
            dataOffset = 12 + headerLen
        }

        val dataSize = npyBytes.size - dataOffset
        val buffer = ByteBuffer.wrap(npyBytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(dataSize / 4)
        buffer.asFloatBuffer().get(floats)

        return floats
    }

    /**
     * Infers the BCP-47 language tag from the voice ID prefix.
     *
     * @param voiceId the voice identifier (e.g. "af_heart")
     * @return BCP-47 language tag (e.g. "en-US")
     */
    private fun inferLanguage(voiceId: String): String = when {
        voiceId.startsWith("a") -> "en-US"
        voiceId.startsWith("b") -> "en-GB"
        voiceId.startsWith("j") -> "ja-JP"
        voiceId.startsWith("z") -> "zh-CN"
        voiceId.startsWith("f") -> "fr-FR"
        voiceId.startsWith("h") -> "hi-IN"
        voiceId.startsWith("i") -> "it-IT"
        voiceId.startsWith("p") -> "pt-BR"
        voiceId.startsWith("e") -> "es-ES"
        else -> "en-US"
    }
}
