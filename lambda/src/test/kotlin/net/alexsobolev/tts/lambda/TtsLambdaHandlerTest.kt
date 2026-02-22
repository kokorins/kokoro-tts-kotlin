package net.alexsobolev.tts.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import io.mockk.mockk
import kotlinx.serialization.json.Json
import net.alexsobolev.tts.core.api.AudioEncoder
import net.alexsobolev.tts.core.api.AudioStorage
import net.alexsobolev.tts.core.api.InferenceEngine
import net.alexsobolev.tts.core.api.PhonemeGenerator
import net.alexsobolev.tts.core.api.VoiceRepository
import net.alexsobolev.tts.core.dto.SynthesisResponse
import net.alexsobolev.tts.core.service.SentencePostProcessor
import net.alexsobolev.tts.core.service.TtsService
import net.alexsobolev.tts.core.service.TurnGapGenerator
import net.alexsobolev.tts.core.usecase.SynthesizeSpeechUseCase
import net.alexsobolev.tts.domain.AudioChunk
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.PhonemeSequence
import net.alexsobolev.tts.domain.SpeechRate
import net.alexsobolev.tts.domain.StoredAudio
import net.alexsobolev.tts.domain.VoiceId
import net.alexsobolev.tts.domain.VoiceProfile
import net.alexsobolev.tts.domain.VoiceSpec
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtsLambdaHandlerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val mockContext = mockk<Context>(relaxed = true)

    private val storedAudio =
        StoredAudio(
            url = "https://example.com/audio.wav",
            key = "tts-audio/af_heart/test.wav",
            expiresInSeconds = 3600,
            sizeBytes = 100,
        )

    private val fakePhonemizer =
        object : PhonemeGenerator {
            override suspend fun generate(text: String) = PhonemeSequence("tɛst")
        }

    private val fakeEngine =
        object : InferenceEngine {
            override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) = floatArrayOf(0.1f, 0.2f)
        }

    private val fakeEncoder =
        object : AudioEncoder {
            override suspend fun encode(samples: FloatArray, format: AudioFormat) = AudioChunk(byteArrayOf(1, 2), format)
        }

    private val fakeStorage =
        object : AudioStorage {
            override suspend fun store(audio: ByteArray, format: AudioFormat, voice: VoiceSpec) = storedAudio
        }

    private val fakeVoiceRepo =
        object : VoiceRepository {
            override suspend fun findById(id: VoiceId) = VoiceProfile(VoiceId("af_heart"), "Heart", floatArrayOf(), "en-US")

            override suspend fun findAll() = listOf(VoiceProfile(VoiceId("af_heart"), "Heart", floatArrayOf(), "en-US"))
        }

    @AfterTest
    fun teardown() {
        GlobalContext.stopKoin()
    }

    private fun setupKoin(
        engine: InferenceEngine = fakeEngine,
        storage: AudioStorage = fakeStorage,
        voiceRepo: VoiceRepository = fakeVoiceRepo,
    ) {
        GlobalContext.stopKoin()
        val service = TtsService(fakePhonemizer, engine, fakeEncoder, TurnGapGenerator(), SentencePostProcessor())
        val useCase = SynthesizeSpeechUseCase(service, storage)
        startKoin {
            modules(
                module {
                    single<SynthesizeSpeechUseCase> { useCase }
                    single<VoiceRepository> { voiceRepo }
                },
            )
        }
    }

    private fun buildEvent(
        method: String,
        path: String,
        body: String? = null,
        base64Encoded: Boolean = false,
    ): APIGatewayV2HTTPEvent {
        val http =
            APIGatewayV2HTTPEvent.RequestContext.Http.builder()
                .withMethod(method)
                .withPath(path)
                .build()
        val rc =
            APIGatewayV2HTTPEvent.RequestContext.builder()
                .withHttp(http)
                .build()
        return APIGatewayV2HTTPEvent.builder()
            .withRequestContext(rc)
            .withBody(body)
            .withIsBase64Encoded(base64Encoded)
            .build()
    }

    @Test
    fun `health endpoint returns 200`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()

        // when
        val response = handler.handleRequest(buildEvent("GET", "/health"), mockContext)

        // then
        assertEquals(200, response.statusCode)
        assertEquals("OK", response.body)
    }

    @Test
    fun `get voices returns 200`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()

        // when
        val response = handler.handleRequest(buildEvent("GET", "/v1/voices"), mockContext)

        // then
        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("af_heart"))
    }

    @Test
    fun `post TTS success returns 200`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()
        val body = """{"turns":[{"voice":"af_heart","text":"Hello"}]}"""

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", body), mockContext)

        // then
        assertEquals(200, response.statusCode)
        val parsed = json.decodeFromString(SynthesisResponse.serializer(), response.body)
        assertEquals("https://example.com/audio.wav", parsed.url)
    }

    @Test
    fun `post TTS empty body returns 400`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", null), mockContext)

        // then
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("required"))
    }

    @Test
    fun `post TTS malformed json returns 400`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", "not json"), mockContext)

        // then
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("Invalid JSON"))
    }

    @Test
    fun `post TTS inference failed returns 500`() {
        // given
        val throwingEngine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) =
                    throw IllegalStateException("onnx crash")
            }
        setupKoin(engine = throwingEngine)
        val handler = TtsLambdaHandler()
        val body = """{"turns":[{"voice":"af_heart","text":"Hello"}]}"""

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", body), mockContext)

        // then
        assertEquals(500, response.statusCode)
    }

    @Test
    fun `post TTS storage failed returns 500`() {
        // given
        val throwingStorage =
            object : AudioStorage {
                override suspend fun store(audio: ByteArray, format: AudioFormat, voice: VoiceSpec) = throw IllegalStateException("s3 down")
            }
        setupKoin(storage = throwingStorage)
        val handler = TtsLambdaHandler()
        val body = """{"turns":[{"voice":"af_heart","text":"Hello"}]}"""

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", body), mockContext)

        // then
        assertEquals(500, response.statusCode)
    }

    @Test
    fun `post TTS invalid input returns 400`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()
        val body = """{"turns":[{"voice":"af_heart+af_star","text":"Hello"}]}"""

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", body), mockContext)

        // then
        assertEquals(400, response.statusCode)
    }

    @Test
    fun `post TTS dialogue empty returns 400`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()
        val body = """{"turns":[]}"""

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", body), mockContext)

        // then
        assertEquals(400, response.statusCode)
    }

    @Test
    fun `unknown route returns 404`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()

        // when
        val response = handler.handleRequest(buildEvent("GET", "/unknown"), mockContext)

        // then
        assertEquals(404, response.statusCode)
        assertTrue(response.body.contains("Not found"))
    }

    @Test
    fun `base64 encoded body is decoded`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()
        val rawBody = """{"turns":[{"voice":"af_heart","text":"Hello"}]}"""
        val encoded = Base64.getEncoder().encodeToString(rawBody.toByteArray())

        // when
        val response =
            handler.handleRequest(
                buildEvent("POST", "/v1/tts", encoded, base64Encoded = true),
                mockContext,
            )

        // then
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `non-base64 body is passed through`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()
        val body = """{"turns":[{"voice":"af_heart","text":"Hello"}]}"""

        // when
        val response =
            handler.handleRequest(
                buildEvent("POST", "/v1/tts", body, base64Encoded = false),
                mockContext,
            )

        // then
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `post MCP with empty body returns 400`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()

        // when
        val response = handler.handleRequest(buildEvent("POST", "/mcp", null), mockContext)

        // then
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("required"))
    }

    @Test
    fun `post MCP with invalid json returns 400`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()

        // when
        val response = handler.handleRequest(buildEvent("POST", "/mcp", "not json"), mockContext)

        // then
        assertEquals(400, response.statusCode)
        assertTrue(response.body.contains("Invalid JSON-RPC"))
    }

    @Test
    fun `post TTS with multiple turns`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()
        val body = """{"turns":[{"voice":"af_heart","text":"First"},{"voice":"af_heart","text":"Second"}]}"""

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", body), mockContext)

        // then
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `post TTS with custom speed and format`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()
        val body = """{"turns":[{"voice":"af_heart","text":"Hello"}],"speed":1.5,"format":"mp3"}"""

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", body), mockContext)

        // then
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `post TTS speed out of range returns 400`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()
        val body = """{"turns":[{"voice":"af_heart","text":"Hello"}],"speed":9.0}"""

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", body), mockContext)

        // then
        assertEquals(400, response.statusCode)
    }

    @Test
    fun `post MCP initialize returns 200`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()
        val body =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{""" +
                """"protocolVersion":"2024-11-05","capabilities":{},""" +
                """"clientInfo":{"name":"test","version":"1.0"}}}"""

        // when
        val response = handler.handleRequest(buildEvent("POST", "/mcp", body), mockContext)

        // then
        assertEquals(200, response.statusCode)
        assertTrue(response.body.contains("kokoro-tts"))
    }

    @Test
    fun `post TTS text too long returns 400`() {
        // given
        setupKoin()
        val handler = TtsLambdaHandler()
        val longText = "a".repeat(6000)
        val body = """{"turns":[{"voice":"af_heart","text":"$longText"}]}"""

        // when
        val response = handler.handleRequest(buildEvent("POST", "/v1/tts", body), mockContext)

        // then
        assertEquals(400, response.statusCode)
    }
}
