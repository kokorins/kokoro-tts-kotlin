package net.alexsobolev.tts.core.service

import net.alexsobolev.tts.domain.Constants
import kotlin.random.Random

/**
 * Generates silence samples to insert between dialogue turns, scaled by speech rate.
 */
class TurnGapGenerator(
    private val sampleRate: Int = Constants.SAMPLE_RATE,
    private val minGapMs: Int = Constants.MIN_TURN_GAP_MS,
    private val maxGapMs: Int = Constants.MAX_TURN_GAP_MS,
    private val random: Random = Random.Default,
) {
    /**
     * Generate a silence gap between dialogue turns.
     *
     * The duration is randomized between minGapMs and maxGapMs,
     * then scaled by 1/speed so faster speech gets shorter pauses.
     * This matches how Kokoro scales punctuation pauses in
     * its alignment code (punct_pause_s / speed_safe).
     *
     * @param speed speech rate multiplier used to scale the gap duration
     * @return float array of zeros representing the silence samples
     */
    fun generate(speed: Double): FloatArray {
        val gapMs = random.nextInt(minGapMs, maxGapMs + 1)
        val scaledMs = (gapMs / speed).toInt()
        val sampleCount = (sampleRate * scaledMs) / 1000
        return FloatArray(sampleCount) // zeros = silence
    }
}
