package net.alexsobolev.tts.core.service

import net.alexsobolev.tts.core.utils.logger
import net.alexsobolev.tts.domain.Constants
import kotlin.math.min

/**
 * Applies volume-envelope post-processing to reinforce question and exclamation intonation.
 *
 * Questions get a rising volume ramp on the tail of the speech (the "uptalk" cue).
 * Exclamations get a gain boost on the attack that fades to normal.
 *
 * Pitch changes are handled upstream via the speed parameter in [TtsService],
 * which produces natural-sounding prosody from the model itself.
 */
class SentencePostProcessor(private val sampleRate: Int = Constants.SAMPLE_RATE) {
    private val logger = logger()

    fun applyIntonation(samples: FloatArray, sentence: String): FloatArray {
        val trimmed = sentence.trimEnd()
        return when {
            trimmed.endsWith('?') -> {
                logger.info("Applying QUESTION volume ramp to '{}' ({} samples)", trimmed.take(50), samples.size)
                applyQuestionIntonation(samples)
            }
            trimmed.endsWith('!') -> {
                logger.info("Applying EXCLAMATION gain boost to '{}' ({} samples)", trimmed.take(50), samples.size)
                applyExclamationIntonation(samples)
            }
            else -> {
                logger.debug("No intonation for statement '{}' ({} samples)", trimmed.take(50), samples.size)
                samples
            }
        }
    }

    /**
     * Question intonation: rising volume on the tail of actual speech.
     *
     * Trailing silence is detected and excluded so the volume ramp
     * targets voiced content. Gain ramps from 1.0 to [QUESTION_TAIL_GAIN]
     * over the last [QUESTION_TAIL_MS] of speech.
     */
    fun applyQuestionIntonation(samples: FloatArray): FloatArray {
        if (samples.size < MIN_SAMPLES_FOR_PROCESSING) {
            logger.warn("Samples too short for question intonation: {} < {}", samples.size, MIN_SAMPLES_FOR_PROCESSING)
            return samples
        }

        val speechEnd = findSpeechEnd(samples)
        if (speechEnd < MIN_SAMPLES_FOR_PROCESSING) {
            logger.warn("Speech portion too short for question intonation: {}", speechEnd)
            return samples
        }

        val tailLen = min(sampleRate * QUESTION_TAIL_MS / 1000, speechEnd)
        val tailStart = speechEnd - tailLen

        val result = samples.copyOf()

        // Rising volume ramp on the tail of speech
        for (i in 0 until tailLen) {
            val t = i.toFloat() / tailLen
            val gain = 1.0f + (QUESTION_TAIL_GAIN - 1.0f) * t * t // quadratic ramp
            result[tailStart + i] = (result[tailStart + i] * gain).coerceIn(-1.0f, 1.0f)
        }

        logger.info(
            "Question intonation: input={}, speechEnd={}, tailStart={}, tailLen={}",
            samples.size,
            speechEnd,
            tailStart,
            tailLen,
        )
        return result
    }

    /**
     * Exclamation intonation: gain boost on the attack.
     *
     * Leading silence is detected and skipped so the boost targets
     * voiced content. Gain fades from [EXCLAMATION_GAIN_BOOST] to 1.0
     * over the first [EXCLAMATION_EMPHASIS_MS] of speech.
     */
    fun applyExclamationIntonation(samples: FloatArray): FloatArray {
        if (samples.size < MIN_SAMPLES_FOR_PROCESSING) {
            logger.warn("Samples too short for exclamation intonation: {} < {}", samples.size, MIN_SAMPLES_FOR_PROCESSING)
            return samples
        }

        val speechStart = findSpeechStart(samples)
        val speechEnd = findSpeechEnd(samples)
        val speechLen = speechEnd - speechStart
        if (speechLen < MIN_SAMPLES_FOR_PROCESSING) {
            logger.warn("Speech portion too short for exclamation: {}", speechLen)
            return samples
        }

        val emphasisLen = min(sampleRate * EXCLAMATION_EMPHASIS_MS / 1000, speechLen)

        val result = samples.copyOf()

        // Gain boost fading from full to none
        for (i in 0 until emphasisLen) {
            val t = i.toFloat() / emphasisLen
            val gain = EXCLAMATION_GAIN_BOOST + (1.0f - EXCLAMATION_GAIN_BOOST) * t
            result[speechStart + i] = (result[speechStart + i] * gain).coerceIn(-1.0f, 1.0f)
        }

        logger.info(
            "Exclamation intonation: input={}, speech=[{},{}], emphasisLen={}",
            samples.size,
            speechStart,
            speechEnd,
            emphasisLen,
        )
        return result
    }

    /**
     * Find the end of speech using a sliding RMS window.
     * Returns the index past the last window with energy above [SILENCE_THRESHOLD_SQ].
     */
    private fun findSpeechEnd(samples: FloatArray, windowSize: Int = SILENCE_WINDOW): Int {
        if (samples.size < windowSize) return samples.size
        for (end in samples.size downTo windowSize) {
            val start = end - windowSize
            var sumSq = 0.0f
            for (i in start until end) {
                sumSq += samples[i] * samples[i]
            }
            if (sumSq / windowSize > SILENCE_THRESHOLD_SQ) return end
        }
        return samples.size
    }

    /**
     * Find the start of speech using a sliding RMS window.
     * Returns the index of the first window with energy above [SILENCE_THRESHOLD_SQ].
     */
    private fun findSpeechStart(samples: FloatArray, windowSize: Int = SILENCE_WINDOW): Int {
        if (samples.size < windowSize) return 0
        for (start in 0..samples.size - windowSize) {
            var sumSq = 0.0f
            for (i in start until start + windowSize) {
                sumSq += samples[i] * samples[i]
            }
            if (sumSq / windowSize > SILENCE_THRESHOLD_SQ) return start
        }
        return 0
    }
}

private const val QUESTION_TAIL_MS = 600
private const val QUESTION_TAIL_GAIN = 1.15f
private const val EXCLAMATION_EMPHASIS_MS = 400
private const val EXCLAMATION_GAIN_BOOST = 1.20f
private const val MIN_SAMPLES_FOR_PROCESSING = 480

/** RMS window size for silence detection (~5ms at 24kHz). */
private const val SILENCE_WINDOW = 120

/** Squared RMS threshold — samples below this are silence. */
private const val SILENCE_THRESHOLD_SQ = 0.001f * 0.001f
