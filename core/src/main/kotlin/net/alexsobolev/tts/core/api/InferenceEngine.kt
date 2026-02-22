package net.alexsobolev.tts.core.api

import net.alexsobolev.tts.domain.PhonemeSequence
import net.alexsobolev.tts.domain.SpeechRate
import net.alexsobolev.tts.domain.VoiceSpec

/**
 * Converts phoneme sequences into raw audio samples.
 *
 * The domain uses this contract to run model inference without
 * knowing which runtime (ONNX, TensorRT, etc.) provides it.
 * Implementations handle tokenization, tensor construction,
 * voice embedding resolution, and output extraction internally.
 *
 * If the phoneme sequence exceeds the model's token limit,
 * the implementation should truncate rather than throw.
 *
 * Thread safety: implementations must be safe to call from
 * multiple coroutines concurrently.
 */
interface InferenceEngine {
    /**
     * Run model inference on a phoneme sequence.
     *
     * @param phonemes the IPA phoneme string to synthesize
     * @param voice single voice or weighted blend of voices
     * @param speed playback speed multiplier
     * @return raw float32 audio samples Hz
     * @throws RuntimeException if the voice is not found or the model session is unavailable
     */
    suspend fun infer(phonemes: PhonemeSequence, voice: VoiceSpec, speed: SpeechRate): FloatArray
}
