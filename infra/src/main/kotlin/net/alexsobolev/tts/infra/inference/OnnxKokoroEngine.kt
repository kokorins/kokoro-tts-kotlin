package net.alexsobolev.tts.infra.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import net.alexsobolev.tts.core.api.InferenceEngine
import net.alexsobolev.tts.core.api.VoiceRepository
import net.alexsobolev.tts.core.utils.logger
import net.alexsobolev.tts.domain.Constants
import net.alexsobolev.tts.domain.PhonemeSequence
import net.alexsobolev.tts.domain.SpeechRate
import net.alexsobolev.tts.domain.VoiceSpec
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.use

/**
 * ONNX Runtime implementation of [InferenceEngine] for the Kokoro TTS model.
 *
 * Tokenizes phonemes, pads with BOS/EOS, resolves voice style embeddings
 * (including weighted blends), and runs the ONNX session on [Dispatchers.IO].
 * The session is lazy-loaded on first inference call.
 */
internal class OnnxKokoroEngine(
    private val modelPath: String,
    private val voiceRepo: VoiceRepository,
    private val tokenizer: KokoroTokenizer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InferenceEngine,
    AutoCloseable {
    private val logger = logger()

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession by lazy {
        logger.debug("Loading ONNX session from {}", modelPath)
        val startMs = System.currentTimeMillis()

        val opts =
            OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
            }
        val s = env.createSession(modelPath, opts)

        val elapsed = System.currentTimeMillis() - startMs
        logger.debug(
            "ONNX session ready in {}ms (inputs: {}, outputs: {})",
            elapsed,
            s.inputNames,
            s.outputNames,
        )
        s
    }

    override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate): FloatArray = withContext(ioDispatcher) {
        var tokenIds = tokenizer.tokenize(phonemes.value)

        if (tokenIds.size > Constants.MAX_PHONEME_TOKENS) {
            logger.warn(
                "Token count {} exceeds limit {}, truncating",
                tokenIds.size,
                Constants.MAX_PHONEME_TOKENS,
            )
            tokenIds = tokenIds.copyOf(Constants.MAX_PHONEME_TOKENS)
        }

        logger.debug(
            "Inference starting: tokens={}, voice={}, speed={}",
            tokenIds.size,
            voice,
            speed.value,
        )
        val startMs = System.currentTimeMillis()

        val padded = LongArray(tokenIds.size + 2)
        padded[0] = Constants.PAD_TOKEN
        tokenIds.copyInto(padded, destinationOffset = 1)
        padded[padded.size - 1] = Constants.PAD_TOKEN

        val inputIdsTensor =
            OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(padded),
                longArrayOf(1, padded.size.toLong()),
            )
        val styleTensor = resolveStyleTensor(voice, tokenIds.size)
        val speedTensor =
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(floatArrayOf(speed.value.toFloat())),
                longArrayOf(1),
            )

        val inputs =
            mapOf(
                "tokens" to inputIdsTensor,
                "style" to styleTensor,
                "speed" to speedTensor,
            )

        val samples =
            inputIdsTensor.use {
                styleTensor.use {
                    speedTensor.use {
                        session.run(inputs).use { results ->
                            val outputTensor = results.get(0) as OnnxTensor
                            val buffer = outputTensor.floatBuffer
                            FloatArray(buffer.remaining()).also { buffer.get(it) }
                        }
                    }
                }
            }

        applyFades(samples)

        val elapsed = System.currentTimeMillis() - startMs
        val durationSec = samples.size.toDouble() / Constants.SAMPLE_RATE
        val rtf = durationSec / (elapsed / 1000.0)
        logger.debug(
            "Inference complete: {}ms, {} samples, {}s audio, RTF={}",
            elapsed,
            samples.size,
            "%.2f".format(durationSec),
            "%.2f".format(rtf),
        )

        samples
    }

    override fun close() {
        logger.debug("Closing ONNX session")
        session.close()
    }

    /**
     * Resolve the voice style embedding tensor for the given voice specification.
     *
     * For single voices, extracts the style vector at the token position.
     * For blended voices, computes a weighted average of multiple embeddings.
     *
     * @param voice the voice specification (single or blended)
     * @param tokenCount number of phoneme tokens, used to index into the embedding
     * @return ONNX tensor with shape (1, 256) containing the style vector
     */
    private suspend fun resolveStyleTensor(voice: VoiceSpec, tokenCount: Int): OnnxTensor {
        val dim = Constants.STYLE_VECTOR_DIM
        val shape = longArrayOf(1, dim.toLong())

        return when (voice) {
            is VoiceSpec.SingleVoice -> {
                val profile =
                    voiceRepo.findById(voice.voiceId)
                        ?: error("Voice ${voice.voiceId} not found")
                val offset = tokenCount * dim
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(profile.embedding, offset, dim),
                    shape,
                )
            }

            is VoiceSpec.BlendedVoice -> {
                val stylesWithWeights =
                    coroutineScope {
                        voice.voices.map { (id, weight) ->
                            async {
                                val profile =
                                    voiceRepo.findById(id)
                                        ?: error("Voice $id not found")
                                val style =
                                    extractStyleVector(
                                        profile.embedding,
                                        tokenCount,
                                    )
                                Pair(style, weight.weight)
                            }
                        }.awaitAll()
                    }

                // Weighted sum of style vectors
                val blended = FloatArray(dim)
                for ((style, w) in stylesWithWeights) {
                    for (i in blended.indices) {
                        blended[i] += style[i] * w
                    }
                }

                // Renormalize to preserve embedding magnitude.
                // Without this, the blended vector has a smaller L2 norm
                // than the originals, causing the model to produce odd audio.
                val blendedNorm = sqrt(blended.sumOf { it.toDouble() * it }).toFloat()
                val targetNorm =
                    stylesWithWeights
                        .sumOf { (style, w) ->
                            sqrt(style.sumOf { it.toDouble() * it }) * w
                        }.toFloat()

                if (blendedNorm > 0f) {
                    val scale = targetNorm / blendedNorm
                    for (i in blended.indices) {
                        blended[i] *= scale
                    }
                }

                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(blended),
                    shape,
                )
            }
        }
    }

    /**
     * Extract a 256-dimensional style vector from the full voice embedding.
     *
     * @param embedding the full voice embedding array of shape (510, 1, 256) flattened
     * @param tokenCount number of phoneme tokens, used as the row index
     * @return 256-element float array representing the style vector
     */
    private fun extractStyleVector(embedding: FloatArray, tokenCount: Int): FloatArray {
        val dim = Constants.STYLE_VECTOR_DIM
        val offset = tokenCount * dim
        return embedding.sliceArray(offset until offset + dim)
    }

    /**
     * Apply short linear fade-in and fade-out to eliminate click artifacts
     * at audio segment boundaries when segments are concatenated.
     */
    private fun applyFades(samples: FloatArray) {
        val fadeSamples = min(FADE_SAMPLES, samples.size / 2)
        for (i in 0 until fadeSamples) {
            val gain = i.toFloat() / fadeSamples
            samples[i] *= gain
            samples[samples.size - 1 - i] *= gain
        }
    }
}

/** 10ms fade at 24 kHz — short enough to be inaudible, long enough to eliminate clicks. */
private const val FADE_SAMPLES = 240
