package net.alexsobolev.tts.app.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.openapi.openAPI
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.coroutines.awaitCancellation
import net.alexsobolev.tts.core.api.VoiceRepository
import net.alexsobolev.tts.core.dto.DialogueRequest
import net.alexsobolev.tts.core.dto.DialogueTurnInput
import net.alexsobolev.tts.core.dto.SynthesisResponse
import net.alexsobolev.tts.core.dto.Voice
import net.alexsobolev.tts.core.dto.VoicesResponse
import net.alexsobolev.tts.core.usecase.SynthesizeSpeechUseCase
import net.alexsobolev.tts.infra.mcp.McpServerFactory
import org.koin.ktor.ext.get
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalKtorApi::class)
fun Application.configureRouting() {
    val voiceRepo: VoiceRepository = get()
    val useCase: SynthesizeSpeechUseCase = get()

    routing {
        swaggerUI(path = "swagger")
        openAPI(path = "openapi")

        get("/health") {
            call.respondText("OK")
        }.describe {
            summary = "Health check"
            description = "Returns OK when the service is running."
            responses {
                HttpStatusCode.OK { description = "Service is healthy" }
            }
        }

        get("/v1/voices") {
            val voices = voiceRepo.findAll().map { Voice(id = it.id.value, language = it.language) }
            call.respond(VoicesResponse(voices))
        }.describe {
            summary = "List available voices"
            description = "Returns all voice profiles supported by the TTS engine."
            responses {
                HttpStatusCode.OK { description = "List of available voices" }
            }
        }

        post("/v1/tts") {
            val dto = call.receive<DialogueRequest>()
            val turns = dto.turns.map { DialogueTurnInput(voice = it.voice, text = it.text) }
            val stored = useCase.execute(turns = turns, speed = dto.speed, format = dto.format).getOrThrow()
            call.respond(
                SynthesisResponse(
                    url = stored.url,
                    key = stored.key,
                    expiresInSeconds = stored.expiresInSeconds,
                    sizeBytes = stored.sizeBytes,
                    format = dto.format,
                    voice = dto.turns.joinToString("+") { it.voice },
                ),
            )
        }.describe {
            summary = "Synthesize speech"
            description = "Converts a multi-turn dialogue to audio and returns a download URL."
            responses {
                HttpStatusCode.OK { description = "Audio synthesized successfully" }
                HttpStatusCode.BadRequest {
                    description = "Invalid request (bad voice, text too long, empty dialogue, malformed JSON)"
                }
                HttpStatusCode.NotFound { description = "Requested voice does not exist" }
                HttpStatusCode.InternalServerError { description = "Inference or storage failure" }
            }
        }

        configureMcp(voiceRepo, useCase)
    }
}

private fun Routing.configureMcp(voiceRepo: VoiceRepository, useCase: SynthesizeSpeechUseCase) {
    val sessions = ConcurrentHashMap<String, KtorSseTransport>()
    route("/mcp") {
        sse {
            val transport = KtorSseTransport("/mcp", this)
            sessions[transport.sessionId] = transport
            try {
                val server = McpServerFactory(voiceRepo, useCase).create()
                server.createSession(transport)
                awaitCancellation()
            } finally {
                sessions.remove(transport.sessionId)
            }
        }
        post {
            val sessionId = call.request.queryParameters["sessionId"]
            val transport = sessionId?.let { sessions[it] }
            if (transport != null) {
                transport.handlePostMessage(call)
            } else {
                call.respond(HttpStatusCode.NotFound, "Unknown session")
            }
        }
    }
}
