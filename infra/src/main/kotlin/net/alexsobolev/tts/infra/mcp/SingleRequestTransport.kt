package net.alexsobolev.tts.infra.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.CompletableDeferred

class SingleRequestTransport : AbstractTransport() {
    private val response = CompletableDeferred<JSONRPCMessage>()

    override suspend fun start() = Unit

    override suspend fun close() = Unit

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        response.complete(message)
    }

    suspend fun handle(message: JSONRPCMessage): JSONRPCMessage? {
        _onMessage?.invoke(message)
        return if (response.isCompleted) response.await() else null
    }
}
