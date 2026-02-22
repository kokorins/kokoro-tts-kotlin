package net.alexsobolev.tts.infra.inference

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KokoroTokenizerTest {
    private fun createTempConfig(vocab: Map<String, Int>): String {
        val entries =
            vocab.entries.joinToString(",") { (k, v) ->
                "\"$k\":$v"
            }
        val json = """{"vocab":{$entries}}"""
        val file = File.createTempFile("kokoro_config_", ".json")
        file.deleteOnExit()
        file.writeText(json)
        return file.absolutePath
    }

    @Test
    fun `tokenize known characters`() {
        // given
        val path = createTempConfig(mapOf("h" to 1, "ɛ" to 2, "l" to 3, "o" to 4))
        val tokenizer = KokoroTokenizer(path)

        // when
        val result = tokenizer.tokenize("hɛlo")

        // then
        assertContentEquals(longArrayOf(1, 2, 3, 4), result)
    }

    @Test
    fun `unknown characters skipped`() {
        // given
        val path = createTempConfig(mapOf("a" to 1, "b" to 2))
        val tokenizer = KokoroTokenizer(path)

        // when
        val result = tokenizer.tokenize("axb")

        // then
        assertContentEquals(longArrayOf(1, 2), result)
    }

    @Test
    fun `empty input returns empty`() {
        // given
        val path = createTempConfig(mapOf("a" to 1))
        val tokenizer = KokoroTokenizer(path)

        // when
        val result = tokenizer.tokenize("")

        // then
        assertEquals(0, result.size)
    }

    @Test
    fun `all unknown returns empty`() {
        // given
        val path = createTempConfig(mapOf("a" to 1))
        val tokenizer = KokoroTokenizer(path)

        // when
        val result = tokenizer.tokenize("xyz")

        // then
        assertEquals(0, result.size)
    }

    @Test
    fun `duplicate characters tokenized`() {
        // given
        val path = createTempConfig(mapOf("a" to 5))
        val tokenizer = KokoroTokenizer(path)

        // when
        val result = tokenizer.tokenize("aaa")

        // then
        assertContentEquals(longArrayOf(5, 5, 5), result)
    }

    @Test
    fun `vocab with many entries`() {
        // given
        val vocab = ('a'..'z').mapIndexed { i, c -> c.toString() to (i + 1) }.toMap()
        val path = createTempConfig(vocab)
        val tokenizer = KokoroTokenizer(path)

        // when
        val tokens = tokenizer.tokenize("abc")

        // then
        assertContentEquals(longArrayOf(1, 2, 3), tokens)
    }

    @Test
    fun `ignores unknown JSON keys`() {
        // given
        val file = File.createTempFile("kokoro_config_", ".json")
        file.deleteOnExit()
        file.writeText("""{"vocab":{"a":1},"extra_key":"ignored"}""")
        val tokenizer = KokoroTokenizer(file.absolutePath)

        // when
        val result = tokenizer.tokenize("a")

        // then
        assertTrue(result.isNotEmpty())
    }
}
