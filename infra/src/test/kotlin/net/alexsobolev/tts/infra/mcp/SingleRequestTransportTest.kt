package net.alexsobolev.tts.infra.mcp

import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SingleRequestTransportTest {
    @Test
    fun `start does nothing`() {
        runTest {
            // when
            val transport = SingleRequestTransport()
            transport.start()
        }
    }

    @Test
    fun `close does nothing`() {
        runTest {
            // when
            val transport = SingleRequestTransport()
            transport.close()
        }
    }

    @Test
    fun `send completes response`() {
        runTest {
            // given
            val transport = SingleRequestTransport()
            transport.onMessage { }
            val message: JSONRPCMessage = JSONRPCResponse(id = RequestId("1"), result = EmptyResult())

            // when
            transport.send(message)
            val result = transport.handle(JSONRPCNotification(method = "test"))

            // then
            assertNotNull(result)
        }
    }

    @Test
    fun `handle without send returns null`() {
        runTest {
            // given
            val transport = SingleRequestTransport()
            transport.onMessage { }
            val notification = JSONRPCNotification(method = "test")

            // when
            val result = transport.handle(notification)

            // then
            assertNull(result)
        }
    }

    @Test
    fun `handle invokes onMessage callback`() {
        runTest {
            // given
            val transport = SingleRequestTransport()
            var received: JSONRPCMessage? = null
            transport.onMessage { msg -> received = msg }

            val responseMessage: JSONRPCMessage = JSONRPCResponse(id = RequestId("1"), result = EmptyResult())
            transport.send(responseMessage)

            // when
            val notification = JSONRPCNotification(method = "ping")
            transport.handle(notification)

            // then
            assertNotNull(received)
        }
    }

    @Test
    fun `send message is returned by handle`() {
        runTest {
            // given
            val transport = SingleRequestTransport()
            transport.onMessage { }
            val responseMessage: JSONRPCMessage = JSONRPCResponse(id = RequestId("42"), result = EmptyResult())
            transport.send(responseMessage)

            // when
            val notification = JSONRPCNotification(method = "test")
            val result = transport.handle(notification)

            // then
            assertNotNull(result)
            assertEquals(responseMessage, result)
        }
    }
}
