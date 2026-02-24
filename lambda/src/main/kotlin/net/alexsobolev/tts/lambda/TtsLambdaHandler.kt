package net.alexsobolev.tts.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.alexsobolev.tts.core.api.VoiceRepository
import net.alexsobolev.tts.core.coreModule
import net.alexsobolev.tts.core.dto.DialogueRequest
import net.alexsobolev.tts.core.dto.DialogueTurnInput
import net.alexsobolev.tts.core.dto.ErrorResponse
import net.alexsobolev.tts.core.dto.SynthesisResponse
import net.alexsobolev.tts.core.dto.Voice
import net.alexsobolev.tts.core.dto.VoicesResponse
import net.alexsobolev.tts.core.usecase.SynthesizeSpeechUseCase
import net.alexsobolev.tts.domain.SynthesisException
import net.alexsobolev.tts.infra.AwsConfig
import net.alexsobolev.tts.infra.InfraConfig
import net.alexsobolev.tts.infra.infraModule
import net.alexsobolev.tts.infra.mcp.McpServerFactory
import net.alexsobolev.tts.infra.mcp.SingleRequestTransport
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.slf4j.LoggerFactory
import java.util.Base64

class TtsLambdaHandler :
    RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>,
    KoinComponent {
    private val logger = LoggerFactory.getLogger(TtsLambdaHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val useCase: SynthesizeSpeechUseCase by inject()
    private val voiceRepository: VoiceRepository by inject()
    private val mcpServer by lazy { McpServerFactory(voiceRepository, useCase).create() }

    init {
        initKoin()
    }

    override fun handleRequest(input: APIGatewayV2HTTPEvent, context: Context): APIGatewayV2HTTPResponse {
        val http = input.requestContext?.http
        val method = http?.method?.uppercase() ?: ""
        val path = http?.path ?: ""

        logger.info("Lambda invocation: {} {}", method, path)

        val body = decodeBody(input)

        return try {
            dispatch(method, path, body)
        } catch (e: Exception) {
            logger.error("Unhandled exception", e)
            error(500, e.message ?: "Internal server error")
        }
    }

    private fun initKoin() {
        if (GlobalContext.getOrNull() != null) return

        val s3Bucket = env("S3_BUCKET", "").takeIf { it.isNotBlank() }
        val aws =
            s3Bucket?.let { bucket ->
                AwsConfig(
                    region = env("AWS_REGION", "eu-central-1"),
                    bucket = bucket,
                )
            }
        val config =
            InfraConfig(
                tokenizerConfigPath = env("TTS_TOKENIZER_CONFIG_PATH", "data/config.json"),
                voicesPath = env("TTS_VOICES_PATH", "data/voices-v1.0.bin"),
                goldDictPath = env("TTS_GOLD_DICT_PATH", "data/us_gold.json"),
                silverDictPath = env("TTS_SILVER_DICT_PATH", "data/us_silver.json"),
                gbGoldDictPath = env("TTS_GB_GOLD_DICT_PATH", "data/gb_gold.json"),
                gbSilverDictPath = env("TTS_GB_SILVER_DICT_PATH", "data/gb_silver.json"),
                posModelPath = env("TTS_POS_MODEL_PATH", "data/en-pos-perceptron.bin"),
                onnxModelPath = env("TTS_ONNX_MODEL_PATH", "data/kokoro-v1.0.int8.onnx"),
                aws = aws,
                storagePrefix = env("STORAGE_PREFIX", "tts-audio"),
                fixesDictPath = env("TTS_FIXES_DICT_PATH", "data/lexicon_fixes.json"),
            )

        startKoin {
            modules(infraModule(config), coreModule())
        }

        logger.info("Koin initialized for Lambda cold start")
    }

    private fun decodeBody(input: APIGatewayV2HTTPEvent): String? = if (input.isBase64Encoded == true && input.body != null) {
        String(Base64.getDecoder().decode(input.body))
    } else {
        input.body
    }

    private fun dispatch(method: String, path: String, body: String?): APIGatewayV2HTTPResponse = when {
        method == "GET" && path == "/health" -> ok("OK")
        method == "GET" && path == "/v1/voices" -> handleVoices()
        method == "POST" && path == "/v1/tts" -> handleTts(body)
        method == "POST" && path == "/mcp" -> handleMcp(body)
        else -> notFound("Not found: $method $path")
    }

    private fun handleVoices(): APIGatewayV2HTTPResponse = runBlocking {
        val voices = voiceRepository.findAll().map { Voice(id = it.id.value, language = it.language) }
        jsonResponse(200, json.encodeToString(VoicesResponse.serializer(), VoicesResponse(voices)))
    }

    private fun handleTts(body: String?): APIGatewayV2HTTPResponse = runBlocking {
        if (body.isNullOrBlank()) {
            return@runBlocking error(400, "Request body is required")
        }

        val dto =
            try {
                json.decodeFromString(DialogueRequest.serializer(), body)
            } catch (e: Exception) {
                return@runBlocking error(400, "Invalid JSON: ${e.message}")
            }

        val turnInputs =
            dto.turns.map { turn ->
                DialogueTurnInput(voice = turn.voice, text = turn.text)
            }

        val result = useCase.execute(turns = turnInputs, speed = dto.speed, format = dto.format)

        result.fold(
            onSuccess = { stored ->
                val response =
                    SynthesisResponse(
                        url = stored.url,
                        key = stored.key,
                        expiresInSeconds = stored.expiresInSeconds,
                        sizeBytes = stored.sizeBytes,
                        format = dto.format,
                        voice = dto.turns.joinToString("+") { it.voice },
                    )
                jsonResponse(200, json.encodeToString(SynthesisResponse.serializer(), response))
            },
            onFailure = { ex ->
                val status = synthesisExceptionStatus(ex)
                error(status, ex.message ?: "Unknown error")
            },
        )
    }

    private fun handleMcp(body: String?): APIGatewayV2HTTPResponse = runBlocking {
        if (body.isNullOrBlank()) {
            return@runBlocking error(400, "Request body is required")
        }

        val mcpJson = McpJson
        val message =
            try {
                mcpJson.decodeFromString(JSONRPCMessage.serializer(), body)
            } catch (e: Exception) {
                return@runBlocking error(400, "Invalid JSON-RPC: ${e.message}")
            }

        val transport = SingleRequestTransport()
        mcpServer.createSession(transport)

        val response = transport.handle(message)
        if (response != null) {
            jsonResponse(200, mcpJson.encodeToString(JSONRPCMessage.serializer(), response))
        } else {
            jsonResponse(200, "{}")
        }
    }

    private fun ok(body: String) = APIGatewayV2HTTPResponse.builder()
        .withStatusCode(200)
        .withHeaders(mapOf("Content-Type" to "text/plain"))
        .withBody(body)
        .build()

    private fun jsonResponse(status: Int, body: String) = APIGatewayV2HTTPResponse.builder()
        .withStatusCode(status)
        .withHeaders(mapOf("Content-Type" to "application/json"))
        .withBody(body)
        .build()

    private fun notFound(message: String) = jsonResponse(404, json.encodeToString(ErrorResponse.serializer(), ErrorResponse(message)))

    private fun error(status: Int, message: String) =
        jsonResponse(status, json.encodeToString(ErrorResponse.serializer(), ErrorResponse(message)))

    private fun synthesisExceptionStatus(ex: Throwable): Int = when (ex) {
        is SynthesisException.VoiceNotFound -> 404
        is SynthesisException.TextTooLong,
        is SynthesisException.SpeedOutOfRange,
        is SynthesisException.DialogueEmpty,
        is SynthesisException.InvalidInput,
        -> 400
        is SynthesisException.InferenceFailed,
        is SynthesisException.StorageFailed,
        -> {
            logger.error(ex.message, ex)
            500
        }
        else -> {
            logger.error("Unexpected error", ex)
            500
        }
    }

    private fun env(name: String, default: String): String = System.getenv(name) ?: default
}
