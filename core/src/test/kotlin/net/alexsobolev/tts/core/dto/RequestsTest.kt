package net.alexsobolev.tts.core.dto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `synthesis request defaults`() {
        // when
        val request = SynthesisRequest(text = "Hello world")

        // then
        assertEquals("Hello world", request.text)
        assertEquals("af_heart", request.voice)
        assertEquals(1.0, request.speed)
        assertEquals("wav", request.format)
    }

    @Test
    fun `synthesis request custom values`() {
        // when
        val request = SynthesisRequest(text = "Hi", voice = "am_adam", speed = 1.5, format = "mp3")

        // then
        assertEquals("Hi", request.text)
        assertEquals("am_adam", request.voice)
        assertEquals(1.5, request.speed)
        assertEquals("mp3", request.format)
    }

    @Test
    fun `synthesis request deserialization`() {
        // given
        val jsonStr = """{"text":"test","voice":"bf_emma","speed":0.8,"format":"mp3"}"""

        // when
        val request = json.decodeFromString(SynthesisRequest.serializer(), jsonStr)

        // then
        assertEquals("test", request.text)
        assertEquals("bf_emma", request.voice)
        assertEquals(0.8, request.speed)
        assertEquals("mp3", request.format)
    }

    @Test
    fun `synthesis request deserialization with defaults`() {
        // given
        val jsonStr = """{"text":"hello"}"""

        // when
        val request = json.decodeFromString(SynthesisRequest.serializer(), jsonStr)

        // then
        assertEquals("hello", request.text)
        assertEquals("af_heart", request.voice)
        assertEquals(1.0, request.speed)
        assertEquals("wav", request.format)
    }

    @Test
    fun `dialogue request deserialization`() {
        // given
        val jsonStr = """{"turns":[{"voice":"af_heart","text":"Hello"}],"speed":1.2,"format":"mp3"}"""

        // when
        val request = json.decodeFromString(DialogueRequest.serializer(), jsonStr)

        // then
        assertEquals(1, request.turns.size)
        assertEquals("af_heart", request.turns[0].voice)
        assertEquals("Hello", request.turns[0].text)
        assertEquals(1.2, request.speed)
        assertEquals("mp3", request.format)
    }

    @Test
    fun `dialogue turn input equality`() {
        // given
        val turn1 = DialogueTurnInput(voice = "af_heart", text = "Hello")
        val turn2 = DialogueTurnInput(voice = "af_heart", text = "Hello")

        // then
        assertEquals(turn1, turn2)
    }
}
