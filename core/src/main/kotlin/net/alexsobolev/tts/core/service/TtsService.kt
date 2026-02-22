package net.alexsobolev.tts.core.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import net.alexsobolev.tts.core.api.AudioEncoder
import net.alexsobolev.tts.core.api.InferenceEngine
import net.alexsobolev.tts.core.api.PhonemeGenerator
import net.alexsobolev.tts.core.utils.logger
import net.alexsobolev.tts.domain.AudioChunk
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.Constants
import net.alexsobolev.tts.domain.DialogueTurn
import net.alexsobolev.tts.domain.SpeechRate
import net.alexsobolev.tts.domain.VoiceSpec

/**
 * Orchestrates the TTS pipeline: text splitting, phonemization, inference, and encoding.
 *
 * Supports single-voice synthesis via [synthesize] and multi-voice dialogue
 * via [synthesizeDialogue], which inserts silence gaps between turns.
 * Long sentences are automatically split on clause boundaries to stay
 * within the model's token limit.
 */
class TtsService(
    private val phonemizer: PhonemeGenerator,
    private val inferenceEngine: InferenceEngine,
    private val audioEncoder: AudioEncoder,
    private val gapGenerator: TurnGapGenerator,
    private val postProcessor: SentencePostProcessor,
) {
    private val logger = logger()

    /**
     * Synthesize speech from a single text input.
     *
     * @param text the natural-language text to convert to speech
     * @param voice the voice specification (single or blended)
     * @param speed playback speed multiplier
     * @param format target audio format (WAV or MP3)
     * @return [Result] wrapping the encoded [AudioChunk] on success
     */
    suspend fun synthesize(
        text: String,
        voice: VoiceSpec,
        speed: SpeechRate,
        format: AudioFormat,
    ): Result<AudioChunk> = runCatching {
        val sentences = splitIntoSentences(text)
        require(sentences.isNotEmpty()) {
            "No sentences found in input text"
        }

        val chunks = mutableListOf<FloatArray>()
        sentenceSamplesFlow(sentences, voice, speed)
            .collect { chunks.add(it) }

        audioEncoder.encode(mergeChunks(chunks), format)
    }

    /**
     * Synthesize a multi-voice dialogue with silence gaps between turns.
     *
     * @param turns ordered list of dialogue turns, each with a voice and text
     * @param speed playback speed multiplier applied to all turns
     * @param format target audio format (WAV or MP3)
     * @return [Result] wrapping the encoded [AudioChunk] on success
     */
    suspend fun synthesizeDialogue(turns: List<DialogueTurn>, speed: SpeechRate, format: AudioFormat): Result<AudioChunk> = runCatching {
        require(turns.isNotEmpty()) {
            "Dialogue must have at least one turn"
        }

        val chunks = mutableListOf<FloatArray>()

        turns.forEachIndexed { index, turn ->
            val sentences = splitIntoSentences(turn.text)
            sentenceSamplesFlow(sentences, turn.voice, speed)
                .collect { chunks.add(it) }

            // Insert silence gap after every turn except the last
            if (index < turns.size - 1) {
                chunks.add(gapGenerator.generate(speed.value))
            }
        }

        audioEncoder.encode(mergeChunks(chunks), format)
    }

    private fun sentenceSamplesFlow(sentences: List<String>, voice: VoiceSpec, speed: SpeechRate): Flow<FloatArray> = sentences
        .asFlow()
        .map { synthesizeSentence(it, voice, speed) }

    private suspend fun synthesizeSentence(sentence: String, voice: VoiceSpec, speed: SpeechRate): FloatArray {
        val trimmed = sentence.trimEnd()
        val isQuestion = trimmed.endsWith('?')
        logger.info("synthesizeSentence: '{}' isQuestion={}", trimmed.take(60), isQuestion)

        if (isQuestion) {
            val clauses = trimmed.split(CLAUSE_BOUNDARY).filter { it.isNotBlank() }
            logger.info("Question has {} clauses: {}", clauses.size, clauses.map { it.take(30) })
            if (clauses.size > 1) {
                val headClauses = clauses.dropLast(1).joinToString("")
                val headPhonemes = phonemizer.generate(headClauses)
                val headSamples = inferenceEngine.infer(headPhonemes, voice, speed)

                val tailClause = clauses.last()
                val tailPhonemes = phonemizer.generate(tailClause)
                val slowerSpeed = questionSpeed(speed)
                val tailSamples = inferenceEngine.infer(tailPhonemes, voice, slowerSpeed)
                logger.info(
                    "Multi-clause question: head={} tail={} samples, speed={}",
                    headSamples.size,
                    tailSamples.size,
                    slowerSpeed,
                )

                val merged = FloatArray(headSamples.size + tailSamples.size)
                headSamples.copyInto(merged)
                tailSamples.copyInto(merged, headSamples.size)
                return postProcessor.applyQuestionIntonation(merged)
            }

            // Single-clause question: synthesize entire sentence slower
            val slowerSpeed = questionSpeed(speed)
            val phonemes = phonemizer.generate(trimmed)
            val samples = inferenceEngine.infer(phonemes, voice, slowerSpeed)
            logger.info("Single-clause question: '{}' → {} samples at speed={}", trimmed.take(40), samples.size, slowerSpeed)
            return postProcessor.applyQuestionIntonation(samples)
        }

        val phonemes = phonemizer.generate(trimmed)
        val samples = inferenceEngine.infer(phonemes, voice, speed)
        logger.info("Single inference: '{}' → {} samples", trimmed.take(40), samples.size)
        return postProcessor.applyIntonation(samples, trimmed)
    }

    private fun mergeChunks(chunks: List<FloatArray>): FloatArray {
        val total = chunks.sumOf { it.size }
        val result = FloatArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    private fun splitIntoSentences(text: String): List<String> {
        val sentences =
            text.split(SENTENCE_BOUNDARY)
                .filter { it.isNotBlank() }
        return sentences.flatMap { splitLongSentence(it) }
    }

    private fun splitLongSentence(sentence: String, maxLength: Int = MAX_SENTENCE_LENGTH): List<String> {
        if (sentence.length <= maxLength) return listOf(sentence)
        val clauses = sentence.split(CLAUSE_BOUNDARY).filter { it.isNotBlank() }
        if (clauses.size <= 1) return listOf(sentence)
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (clause in clauses) {
            if (current.isNotEmpty() && current.length + clause.length > maxLength) {
                chunks.add(current.toString().trim())
                current.clear()
            }
            current.append(clause)
        }
        if (current.isNotBlank()) {
            chunks.add(current.toString().trim())
        }
        return chunks.ifEmpty { listOf(sentence) }
    }

    private fun questionSpeed(speed: SpeechRate): SpeechRate =
        SpeechRate((speed.value * QUESTION_SPEED_FACTOR).coerceIn(Constants.MIN_SPEED, Constants.MAX_SPEED))
}

private const val MAX_SENTENCE_LENGTH = 500
private const val QUESTION_SPEED_FACTOR = 0.92
private val SENTENCE_BOUNDARY = Regex("(?<=[.!?])\\s+")
private val CLAUSE_BOUNDARY = Regex("(?<=[,;:]\\s|—\\s)")
