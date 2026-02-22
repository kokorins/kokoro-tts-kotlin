package net.alexsobolev.tts.app.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import net.alexsobolev.tts.core.dto.ErrorResponse
import net.alexsobolev.tts.domain.SynthesisException

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<SynthesisException> { call, cause ->
            val status =
                when (cause) {
                    is SynthesisException.VoiceNotFound -> HttpStatusCode.NotFound
                    is SynthesisException.TextTooLong,
                    is SynthesisException.SpeedOutOfRange,
                    is SynthesisException.DialogueEmpty,
                    is SynthesisException.InvalidInput,
                    -> HttpStatusCode.BadRequest
                    is SynthesisException.InferenceFailed,
                    is SynthesisException.StorageFailed,
                    -> {
                        call.application.log.error(cause.message, cause)
                        HttpStatusCode.InternalServerError
                    }
                }
            call.respond(status, ErrorResponse(cause.message))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Invalid request body"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "Unexpected error"))
        }
    }
}
