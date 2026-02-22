package net.alexsobolev.tts.app

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.alexsobolev.tts.app.http.configureRouting
import net.alexsobolev.tts.app.http.configureStatusPages
import net.alexsobolev.tts.core.api.AudioEncoder
import net.alexsobolev.tts.core.api.AudioStorage
import net.alexsobolev.tts.core.api.InferenceEngine
import net.alexsobolev.tts.core.api.PhonemeGenerator
import net.alexsobolev.tts.core.api.VoiceRepository
import net.alexsobolev.tts.core.dto.ErrorResponse
import net.alexsobolev.tts.core.dto.VoicesResponse
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
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingTest {
    private val fakeStoredAudio =
        StoredAudio(
            url = "https://s3.example.com/audio.wav",
            key = "tts-audio/af_heart/test.wav",
            expiresInSeconds = 3600,
            sizeBytes = 100,
        )

    private val fakeVoiceRepo =
        object : VoiceRepository {
            override suspend fun findById(id: VoiceId) = VoiceProfile(
                id = VoiceId("af_heart"),
                name = "Heart",
                embedding = floatArrayOf(),
                language = "en-US",
            )

            override suspend fun findAll() = listOf(
                VoiceProfile(
                    id = VoiceId("af_heart"),
                    name = "Heart",
                    embedding = floatArrayOf(),
                    language = "en-US",
                ),
            )
        }

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
            override suspend fun store(audio: ByteArray, format: AudioFormat, voice: VoiceSpec) = fakeStoredAudio
        }

    private fun testModule() = module {
        single<VoiceRepository> { fakeVoiceRepo }
        single<AudioStorage> { fakeStorage }
        single { TtsService(fakePhonemizer, fakeEngine, fakeEncoder, TurnGapGenerator(), SentencePostProcessor()) }
        single { SynthesizeSpeechUseCase(get(), get()) }
    }

    private fun buildTestApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            install(Koin) { modules(testModule()) }
            configureStatusPages()
            configureRouting()
        }
        block()
    }

    private fun buildTestAppWith(
        engine: InferenceEngine = fakeEngine,
        storage: AudioStorage = fakeStorage,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val service = TtsService(fakePhonemizer, engine, fakeEncoder, TurnGapGenerator(), SentencePostProcessor())
        val useCase = SynthesizeSpeechUseCase(service, storage)
        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            install(Koin) {
                modules(
                    module {
                        single<VoiceRepository> { fakeVoiceRepo }
                        single { useCase }
                    },
                )
            }
            configureStatusPages()
            configureRouting()
        }
        block()
    }

    @Test
    fun `health returns ok`() = buildTestApp {
        // when
        client.get("/health").apply {
            // then
            assertEquals(HttpStatusCode.OK, status)
        }
    }

    @Test
    fun `get voices returns 200`() = buildTestApp {
        // when
        client.get("/v1/voices").apply {
            // then
            assertEquals(HttpStatusCode.OK, status)
            val body = Json.decodeFromString<VoicesResponse>(bodyAsText())
            assertTrue(body.voices.isNotEmpty())
            assertEquals("af_heart", body.voices.first().id)
        }
    }

    @Test
    fun `post TTS success returns 200`() = buildTestApp {
        // when
        client.post("/v1/tts") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"turns":[{"voice":"af_heart","text":"Hello world"}]}""")
        }.apply {
            // then
            assertEquals(HttpStatusCode.OK, status)
            assertTrue(bodyAsText().contains("url"))
        }
    }

    @Test
    fun `post TTS malformed json returns 400`() = buildTestApp {
        // when
        client.post("/v1/tts") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("not json at all")
        }.apply {
            // then
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }

    @Test
    fun `post TTS empty turns returns 400`() = buildTestApp {
        // when
        client.post("/v1/tts") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"turns":[]}""")
        }.apply {
            // then
            assertEquals(HttpStatusCode.BadRequest, status)
            val body = Json.decodeFromString<ErrorResponse>(bodyAsText())
            assertTrue(body.error.contains("at least one turn"))
        }
    }

    @Test
    fun `post TTS speed out of range returns 400`() = buildTestApp {
        // when
        client.post("/v1/tts") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"turns":[{"voice":"af_heart","text":"Hi"}],"speed":9.9}""")
        }.apply {
            // then
            assertEquals(HttpStatusCode.BadRequest, status)
            val body = Json.decodeFromString<ErrorResponse>(bodyAsText())
            assertTrue(body.error.contains("out of range"))
        }
    }

    @Test
    fun `post TTS inference failed returns 500`() {
        // given
        val throwingEngine =
            object : InferenceEngine {
                override suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate) =
                    throw IllegalStateException("onnx crash")
            }

        buildTestAppWith(engine = throwingEngine) {
            // when
            client.post("/v1/tts") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"turns":[{"voice":"af_heart","text":"Hello"}]}""")
            }.apply {
                // then
                assertEquals(HttpStatusCode.InternalServerError, status)
            }
        }
    }

    @Test
    fun `post TTS storage failed returns 500`() {
        // given
        val throwingStorage =
            object : AudioStorage {
                override suspend fun store(audio: ByteArray, format: AudioFormat, voice: VoiceSpec) = throw IllegalStateException("s3 down")
            }

        buildTestAppWith(storage = throwingStorage) {
            // when
            client.post("/v1/tts") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody("""{"turns":[{"voice":"af_heart","text":"Hello"}]}""")
            }.apply {
                // then
                assertEquals(HttpStatusCode.InternalServerError, status)
            }
        }
    }

    @Test
    fun `post TTS invalid input returns 400`() = buildTestApp {
        // when
        client.post("/v1/tts") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"turns":[{"voice":"af_heart+af_star","text":"Hello"}]}""")
        }.apply {
            // then
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }

    @Test
    fun `post MCP unknown session returns 404`() = buildTestApp {
        // when
        client.post("/mcp?sessionId=nonexistent") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{}")
        }.apply {
            // then
            assertEquals(HttpStatusCode.NotFound, status)
        }
    }
}
