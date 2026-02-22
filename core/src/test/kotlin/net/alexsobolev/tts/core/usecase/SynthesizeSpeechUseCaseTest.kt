package net.alexsobolev.tts.core.usecase

import kotlinx.coroutines.test.runTest
import net.alexsobolev.tts.core.api.AudioEncoder
import net.alexsobolev.tts.core.api.AudioStorage
import net.alexsobolev.tts.core.api.InferenceEngine
import net.alexsobolev.tts.core.api.PhonemeGenerator
import net.alexsobolev.tts.core.dto.DialogueTurnInput
import net.alexsobolev.tts.core.service.SentencePostProcessor
import net.alexsobolev.tts.core.service.TtsService
import net.alexsobolev.tts.core.service.TurnGapGenerator
import net.alexsobolev.tts.domain.AudioChunk
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.PhonemeSequence
import net.alexsobolev.tts.domain.SpeechRate
import net.alexsobolev.tts.domain.StoredAudio
import net.alexsobolev.tts.domain.SynthesisException
import net.alexsobolev.tts.domain.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SynthesizeSpeechUseCaseTest {
    private val storedAudio =
        StoredAudio(
            url = "https://example.com/audio.wav",
            key = "tts-audio/af_heart/test.wav",
            expiresInSeconds = 3600,
            sizeBytes = 100,
        )

    private fun buildUseCase(storageResult: StoredAudio = storedAudio, storageFails: Boolean = false): SynthesizeSpeechUseCase {
        val phonemizer =
            object : PhonemeGenerator {
                override suspend fun generate(text: String) = PhonemeSequence("tɛst")
            }
        val engine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) = floatArrayOf(0.1f, 0.2f)
            }
        val encoder =
            object : AudioEncoder {
                override suspend fun encode(samples: FloatArray, format: AudioFormat) = AudioChunk(byteArrayOf(1, 2), format)
            }
        val storage =
            object : AudioStorage {
                override suspend fun store(audio: ByteArray, format: AudioFormat, voice: VoiceSpec): StoredAudio {
                    if (storageFails) error("S3 down")
                    return storageResult
                }
            }
        val ttsService = TtsService(phonemizer, engine, encoder, TurnGapGenerator(), SentencePostProcessor())
        return SynthesizeSpeechUseCase(ttsService, storage)
    }

    @Test
    fun `successful synthesis`() {
        runTest {
            // given
            val useCase = buildUseCase()
            val turns = listOf(DialogueTurnInput("af_heart", "Hello world"))

            // when
            val result = useCase.execute(turns)

            // then
            assertTrue(result.isSuccess)
            assertNotNull(result.getOrNull())
        }
    }

    @Test
    fun `empty turns returns dialogue empty`() {
        runTest {
            // given
            val useCase = buildUseCase()

            // when
            val result = useCase.execute(emptyList())

            // then
            assertTrue(result.isFailure)
            assertIs<SynthesisException.DialogueEmpty>(result.exceptionOrNull())
        }
    }

    @Test
    fun `speed too high returns speed out of range`() {
        runTest {
            // given
            val useCase = buildUseCase()
            val turns = listOf(DialogueTurnInput("af_heart", "Hello"))

            // when
            val result = useCase.execute(turns, speed = 5.0)

            // then
            assertTrue(result.isFailure)
            assertIs<SynthesisException.SpeedOutOfRange>(result.exceptionOrNull())
        }
    }

    @Test
    fun `speed too low returns speed out of range`() {
        runTest {
            // given
            val useCase = buildUseCase()
            val turns = listOf(DialogueTurnInput("af_heart", "Hello"))

            // when
            val result = useCase.execute(turns, speed = 0.1)

            // then
            assertTrue(result.isFailure)
            assertIs<SynthesisException.SpeedOutOfRange>(result.exceptionOrNull())
        }
    }

    @Test
    fun `text too long returns text too long`() {
        runTest {
            // given
            val useCase = buildUseCase()
            val longText = "a".repeat(5001)
            val turns = listOf(DialogueTurnInput("af_heart", longText))

            // when
            val result = useCase.execute(turns)

            // then
            assertTrue(result.isFailure)
            assertIs<SynthesisException.TextTooLong>(result.exceptionOrNull())
        }
    }

    @Test
    fun `storage failure returns storage failed`() {
        runTest {
            // given
            val useCase = buildUseCase(storageFails = true)
            val turns = listOf(DialogueTurnInput("af_heart", "Hello"))

            // when
            val result = useCase.execute(turns)

            // then
            assertTrue(result.isFailure)
            assertIs<SynthesisException.StorageFailed>(result.exceptionOrNull())
        }
    }

    @Test
    fun `wav format default`() {
        runTest {
            // given
            val useCase = buildUseCase()
            val turns = listOf(DialogueTurnInput("af_heart", "Hello"))

            // when
            val result = useCase.execute(turns, format = "wav")

            // then
            assertTrue(result.isSuccess)
        }
    }

    @Test
    fun `mp3 format`() {
        runTest {
            // given
            val useCase = buildUseCase()
            val turns = listOf(DialogueTurnInput("af_heart", "Hello"))

            // when
            val result = useCase.execute(turns, format = "mp3")

            // then
            assertTrue(result.isSuccess)
        }
    }

    @Test
    fun `blended voice spec`() {
        runTest {
            // given
            val useCase = buildUseCase()
            val turns =
                listOf(
                    DialogueTurnInput("af_heart:0.6+af_star:0.4", "Hello"),
                )

            // when
            val result = useCase.execute(turns)

            // then
            assertTrue(result.isSuccess)
        }
    }

    @Test
    fun `multiple turns`() {
        runTest {
            // given
            val useCase = buildUseCase()
            val turns =
                listOf(
                    DialogueTurnInput("af_heart", "First turn."),
                    DialogueTurnInput("af_star", "Second turn."),
                )

            // when
            val result = useCase.execute(turns)

            // then
            assertTrue(result.isSuccess)
        }
    }

    @Test
    fun `invalid voice blend spec returns invalid input`() {
        runTest {
            // given
            val useCase = buildUseCase()
            val turns = listOf(DialogueTurnInput("af_heart:0.6+af_star:notanumber", "Hello"))

            // when
            val result = useCase.execute(turns)

            // then
            assertTrue(result.isFailure)
            assertIs<SynthesisException.InvalidInput>(result.exceptionOrNull())
        }
    }

    @Test
    fun `malformed blend component returns invalid input`() {
        runTest {
            // given
            val useCase = buildUseCase()
            val turns = listOf(DialogueTurnInput("af_heart+af_star", "Hello"))

            // when
            val result = useCase.execute(turns)

            // then
            assertTrue(result.isFailure)
            assertIs<SynthesisException.InvalidInput>(result.exceptionOrNull())
        }
    }
}
