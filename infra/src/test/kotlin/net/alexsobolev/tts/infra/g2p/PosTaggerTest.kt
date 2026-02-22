package net.alexsobolev.tts.infra.g2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PosTaggerTest {
    private val tagger = OpenNlpPosTagger("../data/en-pos-perceptron.bin")

    @Test
    fun `tags basic sentence`() {
        // given
        val tokens = listOf("people", "still", "live", "there")

        // when
        val tags = tagger.tag(tokens)

        // then
        assertEquals(4, tags.size)
    }

    @Test
    fun `produces Penn Treebank tags`() {
        // given
        val tokens = listOf("the", "record", "was", "broken")

        // when
        val tags = tagger.tag(tokens)

        // then — fine-grained PTB tags, not coarse UD tags
        assertEquals("DT", tags[0])
        assertEquals("NN", tags[1])
        assertEquals("VBD", tags[2])
        assertEquals("VBN", tags[3])
    }

    @Test
    fun `verb is tagged with VB prefix`() {
        // given
        val tokens = listOf("they", "live", "here")

        // when
        val tags = tagger.tag(tokens)

        // then
        assertTrue(tags[1].startsWith("VB"), "Expected VB* tag for 'live', got ${tags[1]}")
    }

    @Test
    fun `noun is tagged with NN prefix`() {
        // given
        val tokens = listOf("the", "record", "is", "broken")

        // when
        val tags = tagger.tag(tokens)

        // then
        assertTrue(tags[1].startsWith("NN"), "Expected NN* tag for 'record', got ${tags[1]}")
    }

    @Test
    fun `adjective is tagged with JJ prefix`() {
        // given
        val tokens = listOf("the", "live", "performance")

        // when
        val tags = tagger.tag(tokens)

        // then
        assertTrue(tags[1].startsWith("JJ"), "Expected JJ* tag for 'live' as adjective, got ${tags[1]}")
    }

    @Test
    fun `empty list returns empty`() {
        // when
        val tags = tagger.tag(emptyList())

        // then
        assertEquals(0, tags.size)
    }

    @Test
    fun `past tense read detected with original case`() {
        // given
        val tokens = listOf("Yesterday", "I", "read", "the", "entire", "novel")

        // when
        val tags = tagger.tag(tokens)

        // then — perceptron correctly tags past-tense "read" as VBD
        assertEquals("VBD", tags[2], "Expected VBD for past-tense 'read', got ${tags[2]}")
    }
}
