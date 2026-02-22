package net.alexsobolev.tts.infra.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.alexsobolev.tts.core.api.VoiceRepository
import net.alexsobolev.tts.core.dto.DialogueTurnInput
import net.alexsobolev.tts.core.dto.SynthesisResponse
import net.alexsobolev.tts.core.usecase.SynthesizeSpeechUseCase
import net.alexsobolev.tts.core.utils.logger
import net.alexsobolev.tts.domain.SynthesisException
import kotlin.time.Duration.Companion.minutes

class McpServerFactory(private val voiceRepository: VoiceRepository, private val useCase: SynthesizeSpeechUseCase) {
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true }

    fun create(): Server {
        val options =
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ).apply {
                timeout = 30.minutes
            }
        val server = Server(Implementation(name = "kokoro-tts", version = "0.0.1"), options)

        server.addTool(
            name = "list_voices",
            description = "List all available TTS voices with their IDs and languages.",
        ) {
            val profiles = voiceRepository.findAll()
            val lines = profiles.joinToString("\n") { "${it.id.value} (${it.language})" }
            CallToolResult(content = listOf(TextContent(lines)))
        }

        registerSynthesizeSpeechTool(server)
        registerSynthesizeDialogueTool(server)

        return server
    }

    private fun registerSynthesizeSpeechTool(server: Server) {
        server.addTool(
            name = "synthesize_speech",
            description =
            "Synthesize text to speech. Returns a presigned URL to download the audio file. " +
                "CRITICAL: wrap ALL location names, cities, countries, person names, foreign words, " +
                "and non-English proper nouns in (word)[IPA phonemes] annotations to avoid " +
                "mispronunciation (e.g. '(Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː]', " +
                "'(São Paulo)[sˌaʊ̃ pˈaʊ̯lʊ]').",
            inputSchema =
            ToolSchema(
                properties =
                buildJsonObject {
                    putJsonObject("text") {
                        put("type", "string")
                        put(
                            "description",
                            "The text to synthesize into speech. " +
                                "Wrap all location names, cities, countries, person names, foreign words, " +
                                "and non-English proper nouns in (word)[IPA] annotations " +
                                "for correct pronunciation, e.g. '(Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː]'.",
                        )
                    }
                    putJsonObject("voice") {
                        put("type", "string")
                        put(
                            "description",
                            "Voice ID or blend of up to 4 voices (default: af_heart). " +
                                "Blend syntax: 'af_heart:0.6+bf_emma:0.4' (weights must sum to 1.0). " +
                                "Use list_voices to see available voices.",
                        )
                    }
                    putJsonObject("speed") {
                        put("type", "number")
                        put("description", "Speech rate multiplier from 0.5 to 2.0 (default: 1.0)")
                    }
                    putJsonObject("format") {
                        put("type", "string")
                        put("description", "Output audio format: mp3 or wav (default: mp3)")
                    }
                },
                required = listOf("text"),
            ),
        ) { request ->
            val text =
                request.arguments?.get("text")?.jsonPrimitive?.content
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent("Error: 'text' parameter is required")),
                        isError = true,
                    )
            val voice = request.arguments?.get("voice")?.jsonPrimitive?.content ?: "af_heart"
            val speed = request.arguments?.get("speed")?.jsonPrimitive?.doubleOrNull ?: 1.0
            val format = request.arguments?.get("format")?.jsonPrimitive?.content ?: "mp3"

            val turns = listOf(DialogueTurnInput(voice = voice, text = text))
            executeSynthesis(turns, speed, format, voice)
        }
    }

    private fun registerSynthesizeDialogueTool(server: Server) {
        server.addTool(
            name = "synthesize_dialogue",
            description =
            "Synthesize a multi-turn dialogue with different voices. " +
                "Returns a presigned URL to download the combined audio file. " +
                "CRITICAL: wrap ALL location names, cities, countries, person names, foreign words, " +
                "and non-English proper nouns in (word)[IPA phonemes] annotations to avoid " +
                "mispronunciation (e.g. '(Machu Picchu)[mˈɑːtʃuː pˈiːtʃuː]', " +
                "'(São Paulo)[sˌaʊ̃ pˈaʊ̯lʊ]').",
            inputSchema = dialogueToolSchema(),
        ) { request ->
            val turnsJson =
                request.arguments?.get("turns")?.jsonArray
                    ?: return@addTool CallToolResult(
                        content = listOf(TextContent("Error: 'turns' parameter is required")),
                        isError = true,
                    )

            val turns =
                turnsJson.map { element ->
                    val obj = element.jsonObject
                    val voice =
                        obj["voice"]?.jsonPrimitive?.content
                            ?: return@addTool CallToolResult(
                                content = listOf(TextContent("Error: each turn must have a 'voice' field")),
                                isError = true,
                            )
                    val text =
                        obj["text"]?.jsonPrimitive?.content
                            ?: return@addTool CallToolResult(
                                content = listOf(TextContent("Error: each turn must have a 'text' field")),
                                isError = true,
                            )
                    DialogueTurnInput(voice = voice, text = text)
                }

            val speed = request.arguments?.get("speed")?.jsonPrimitive?.doubleOrNull ?: 1.0
            val format = request.arguments?.get("format")?.jsonPrimitive?.content ?: "mp3"
            val voiceSummary = turns.joinToString("+") { it.voice }

            executeSynthesis(turns, speed, format, voiceSummary)
        }
    }

    private fun dialogueToolSchema(): ToolSchema = ToolSchema(
        properties =
        buildJsonObject {
            putJsonObject("turns") {
                put("type", "array")
                put("description", "Dialogue turns. Each turn has a voice and text.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("voice") {
                            put("type", "string")
                            put(
                                "description",
                                "Voice ID or blend of up to 4 voices " +
                                    "(e.g. 'af_heart' or 'af_heart:0.6+bf_emma:0.4', weights must sum to 1.0)",
                            )
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put(
                                "description",
                                "Text for this turn. " +
                                    "Wrap all location names, cities, countries, person names, " +
                                    "foreign words, and non-English proper nouns in (word)[IPA] " +
                                    "annotations for correct pronunciation.",
                            )
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("voice"))
                        add(JsonPrimitive("text"))
                    }
                }
            }
            putJsonObject("speed") {
                put("type", "number")
                put("description", "Speech rate multiplier from 0.5 to 2.0 (default: 1.0)")
            }
            putJsonObject("format") {
                put("type", "string")
                put("description", "Output audio format: mp3 or wav (default: mp3)")
            }
        },
        required = listOf("turns"),
    )

    private suspend fun executeSynthesis(
        turns: List<DialogueTurnInput>,
        speed: Double,
        format: String,
        voiceLabel: String,
    ): CallToolResult {
        val result = useCase.execute(turns = turns, speed = speed, format = format)

        return result.fold(
            onSuccess = { stored ->
                val response =
                    SynthesisResponse(
                        url = stored.url,
                        key = stored.key,
                        expiresInSeconds = stored.expiresInSeconds,
                        sizeBytes = stored.sizeBytes,
                        format = format,
                        voice = voiceLabel,
                    )
                CallToolResult(
                    content = listOf(TextContent(json.encodeToString(SynthesisResponse.serializer(), response))),
                )
            },
            onFailure = { ex ->
                val message =
                    when (ex) {
                        is SynthesisException.VoiceNotFound -> "Voice not found: ${ex.voiceId}"
                        is SynthesisException.TextTooLong -> "Text too long: ${ex.length} chars (max ${ex.max})"
                        is SynthesisException.SpeedOutOfRange -> "Speed out of range: ${ex.speed}"
                        is SynthesisException.DialogueEmpty -> "Dialogue must have at least one turn"
                        is SynthesisException.InferenceFailed -> "Synthesis failed: ${ex.reason}"
                        is SynthesisException.StorageFailed -> "Storage failed: ${ex.reason}"
                        else -> "Unexpected error: ${ex.message}"
                    }
                logger.error("Synthesis error: {}", message)
                CallToolResult(content = listOf(TextContent("Error: $message")), isError = true)
            },
        )
    }
}
