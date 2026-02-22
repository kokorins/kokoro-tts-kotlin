package net.alexsobolev.tts.app.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Custom SSE transport for MCP that uses Ktor 3.4.0's [ServerSentEvent] API directly.
 *
 * The MCP SDK's built-in [io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport] is compiled
 * against Ktor SSE 3.2.3 and is binary-incompatible with Ktor 3.4.0 (the `send()` overload changed
 * from `abstract` to `default`). This transport bypasses the incompatibility by calling
 * `session.send(ServerSentEvent(...))` which is the stable abstract overload in both versions.
 */
@OptIn(ExperimentalUuidApi::class)
class KtorSseTransport(private val endpoint: String, private val session: ServerSSESession) : AbstractTransport() {
    val sessionId: String = Uuid.random().toString()
    private val initialized = AtomicBoolean(false)

    override suspend fun start() {
        check(initialized.compareAndSet(false, true)) { "SSE transport already started" }
        session.send(ServerSentEvent(data = "$endpoint?sessionId=$sessionId", event = "endpoint"))
        val job = session.coroutineContext[Job]
        job?.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                _onError.invoke(cause)
            } else {
                _onClose.invoke()
            }
        }
    }

    override suspend fun close() {
        session.close()
        _onClose.invoke()
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        check(initialized.get()) { "Not connected" }
        val json = McpJson.encodeToString(JSONRPCMessage.serializer(), message)
        session.send(ServerSentEvent(data = json, event = "message"))
    }

    suspend fun handlePostMessage(call: ApplicationCall) {
        if (!initialized.get()) {
            call.respondText("SSE connection not established", status = HttpStatusCode.InternalServerError)
            _onError.invoke(IllegalStateException("SSE connection not established"))
            return
        }
        try {
            val body = call.receiveText()
            val message = McpJson.decodeFromString(JSONRPCMessage.serializer(), body)
            _onMessage.invoke(message)
            call.respondText("Accepted", status = HttpStatusCode.Accepted)
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            call.respondText("Failed to parse message: ${e.message}", status = HttpStatusCode.BadRequest)
            _onError.invoke(e)
        }
    }
}
