package net.alexsobolev.tts.core.service

import kotlinx.coroutines.test.runTest
import net.alexsobolev.tts.core.api.AudioEncoder
import net.alexsobolev.tts.core.api.InferenceEngine
import net.alexsobolev.tts.core.api.PhonemeGenerator
import net.alexsobolev.tts.domain.AudioChunk
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.DialogueTurn
import net.alexsobolev.tts.domain.PhonemeSequence
import net.alexsobolev.tts.domain.SampleRate
import net.alexsobolev.tts.domain.SpeechRate
import net.alexsobolev.tts.domain.VoiceId
import net.alexsobolev.tts.domain.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsServiceTest {
    private val wavFormat = AudioFormat.WAV(SampleRate(24000))
    private val speed = SpeechRate(1.0)
    private val voice = VoiceSpec.SingleVoice(VoiceId("af_heart"))
    private val sampleData = floatArrayOf(0.1f, 0.2f, 0.3f)
    private val encodedBytes = byteArrayOf(1, 2, 3, 4)
    private val gapGenerator = TurnGapGenerator()
    private val postProcessor = SentencePostProcessor()

    private fun buildService(
        phonemes: PhonemeSequence = PhonemeSequence("hɛˈloʊ"),
        samples: FloatArray = sampleData,
        encoded: ByteArray = encodedBytes,
    ): TtsService {
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String) = phonemes
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) = samples
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat) = AudioChunk(encoded, format)
            }
        return TtsService(phonemizer, engine, encoder, gapGenerator, postProcessor)
    }

    @Test
    fun `synthesize single sentence`() = runTest {
        // given
        val service = buildService()

        // when
        val result = service.synthesize("Hello.", voice, speed, wavFormat)

        // then
        assertTrue(result.isSuccess)
        assertEquals(wavFormat, result.getOrThrow().format)
    }

    @Test
    fun `synthesize multiple sentences`() = runTest {
        // given
        val sentences = mutableListOf<String>()
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String): PhonemeSequence {
                    sentences.add(text)
                    return PhonemeSequence("tɛst")
                }
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) = floatArrayOf(1.0f)
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat) = AudioChunk(byteArrayOf(1), format)
            }
        val service = TtsService(phonemizer, engine, encoder, gapGenerator, postProcessor)

        // when
        service.synthesize("First. Second! Third?", voice, speed, wavFormat)

        // then
        assertEquals(3, sentences.size)
    }

    @Test
    fun `synthesize dialogue single turn`() = runTest {
        // given
        val service = buildService()
        val turns = listOf(DialogueTurn(voice, "Hello."))

        // when
        val result = service.synthesizeDialogue(turns, speed, wavFormat)

        // then
        assertTrue(result.isSuccess)
    }

    @Test
    fun `synthesize dialogue multiple turns includes gaps`() = runTest {
        // given
        var encodedSamples: FloatArray? = null
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String) = PhonemeSequence("tɛst")
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) = floatArrayOf(0.5f, 0.5f)
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat): AudioChunk {
                    encodedSamples = samples
                    return AudioChunk(byteArrayOf(1), format)
                }
            }
        val service = TtsService(phonemizer, engine, encoder, gapGenerator, postProcessor)
        val turns =
            listOf(
                DialogueTurn(voice, "Turn one."),
                DialogueTurn(voice, "Turn two."),
            )

        // when
        service.synthesizeDialogue(turns, speed, wavFormat)

        // then
        // 2 samples per turn (2 turns) + gap samples between turns
        // Gap is at least 6000 samples at 24kHz with default params
        assertTrue(encodedSamples!!.size > 4)
    }

    @Test
    fun `synthesize dialogue empty turns fails`() = runTest {
        // given
        val service = buildService()

        // when
        val result =
            service.synthesizeDialogue(emptyList(), speed, wavFormat)

        // then
        assertTrue(result.isFailure)
    }

    @Test
    fun `synthesize blank text fails`() = runTest {
        // given
        val service = buildService()

        // when
        val result = service.synthesize("   ", voice, speed, wavFormat)

        // then
        assertTrue(result.isFailure)
    }

    @Test
    fun `synthesize dialogue no gap after last turn`() = runTest {
        // given
        var encodedSamples: FloatArray? = null
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String) = PhonemeSequence("t")
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) = floatArrayOf(1.0f)
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat): AudioChunk {
                    encodedSamples = samples
                    return AudioChunk(byteArrayOf(1), format)
                }
            }
        val service = TtsService(phonemizer, engine, encoder, gapGenerator, postProcessor)
        val turns = listOf(DialogueTurn(voice, "Hello."))

        // when
        service.synthesizeDialogue(turns, speed, wavFormat)

        // then
        // Only 1 sample from inference, no gap
        assertEquals(1, encodedSamples!!.size)
    }

    @Test
    fun `question sentence uses slower speed for last clause`() = runTest {
        // given
        val speeds = mutableListOf<Double>()
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String) = PhonemeSequence("tɛst")
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate): FloatArray {
                    speeds.add(speed.value)
                    return FloatArray(1000) { 0.5f }
                }
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat) = AudioChunk(byteArrayOf(1), format)
            }
        val service = TtsService(phonemizer, engine, encoder, gapGenerator, postProcessor)

        // when
        // Question with clause boundary (comma): triggers split inference
        service.synthesize("Are you sure, about this?", voice, speed, wavFormat)

        // then
        // Should have 2 infer calls: head at 1.0, tail at 0.92
        assertEquals(2, speeds.size)
        assertEquals(1.0, speeds[0], 0.001)
        assertEquals(0.92, speeds[1], 0.001)
    }

    @Test
    fun `question without clauses uses slower speed`() = runTest {
        // given
        val speeds = mutableListOf<Double>()
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String) = PhonemeSequence("tɛst")
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate): FloatArray {
                    speeds.add(speed.value)
                    return FloatArray(1000) { 0.5f }
                }
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat) = AudioChunk(byteArrayOf(1), format)
            }
        val service = TtsService(phonemizer, engine, encoder, gapGenerator, postProcessor)

        // when
        // Single-clause question: inference at slower speed (0.92)
        service.synthesize("Really?", voice, speed, wavFormat)

        // then
        assertEquals(1, speeds.size)
        assertEquals(0.92, speeds[0], 0.001)
    }

    @Test
    fun `exclamation applies post processing`() = runTest {
        // given
        var encodedSamples: FloatArray? = null
        val originalSamples = FloatArray(2400) { 0.5f }
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String) = PhonemeSequence("tɛst")
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) = originalSamples.copyOf()
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat): AudioChunk {
                    encodedSamples = samples
                    return AudioChunk(byteArrayOf(1), format)
                }
            }
        val service = TtsService(phonemizer, engine, encoder, gapGenerator, postProcessor)

        // when
        service.synthesize("Wow!", voice, speed, wavFormat)

        // then
        // The first samples should be boosted (gain > 1.0)
        assertTrue(encodedSamples!![0] > originalSamples[0])
    }

    @Test
    fun `statement sentence is unmodified`() = runTest {
        // given
        var encodedSamples: FloatArray? = null
        val originalSamples = FloatArray(1000) { 0.5f }
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String) = PhonemeSequence("tɛst")
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) = originalSamples.copyOf()
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat): AudioChunk {
                    encodedSamples = samples
                    return AudioChunk(byteArrayOf(1), format)
                }
            }
        val service = TtsService(phonemizer, engine, encoder, gapGenerator, postProcessor)

        // when
        service.synthesize("Hello.", voice, speed, wavFormat)

        // then
        // Statement should pass through unmodified
        assertTrue(encodedSamples!!.contentEquals(originalSamples))
    }

    @Test
    fun `long sentence is split at clause boundary`() = runTest {
        // given
        val sentences = mutableListOf<String>()
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String): PhonemeSequence {
                    sentences.add(text)
                    return PhonemeSequence("tɛst")
                }
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) = floatArrayOf(1.0f)
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat) = AudioChunk(byteArrayOf(1), format)
            }
        val service = TtsService(phonemizer, engine, encoder, gapGenerator, postProcessor)
        val clause = "this is a clause with some text, "
        val longText = clause.repeat(20).trimEnd() + "."

        // when
        service.synthesize(longText, voice, speed, wavFormat)

        // then
        assertTrue(sentences.size > 1, "Expected long sentence to be split, got ${sentences.size}")
    }

    @Test
    fun `long sentence with no clauses is not split`() = runTest {
        // given
        val sentences = mutableListOf<String>()
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String): PhonemeSequence {
                    sentences.add(text)
                    return PhonemeSequence("tɛst")
                }
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) = floatArrayOf(1.0f)
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat) = AudioChunk(byteArrayOf(1), format)
            }
        val service = TtsService(phonemizer, engine, encoder, gapGenerator, postProcessor)
        val longText = "a".repeat(600) + "."

        // when
        service.synthesize(longText, voice, speed, wavFormat)

        // then
        // Without clause boundaries it can't be split
        assertEquals(1, sentences.size)
    }
}
