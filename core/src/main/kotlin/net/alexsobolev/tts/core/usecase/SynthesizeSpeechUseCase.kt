package net.alexsobolev.tts.core.usecase

import net.alexsobolev.tts.core.api.AudioStorage
import net.alexsobolev.tts.core.dto.DialogueTurnInput
import net.alexsobolev.tts.core.service.TtsService
import net.alexsobolev.tts.domain.AudioFormat
import net.alexsobolev.tts.domain.Constants
import net.alexsobolev.tts.domain.DialogueTurn
import net.alexsobolev.tts.domain.SampleRate
import net.alexsobolev.tts.domain.SpeechRate
import net.alexsobolev.tts.domain.StoredAudio
import net.alexsobolev.tts.domain.SynthesisException
import net.alexsobolev.tts.domain.VoiceBlendWeight
import net.alexsobolev.tts.domain.VoiceId
import net.alexsobolev.tts.domain.VoiceSpec

/**
 * Result type for synthesis use cases.
 *
 * On success wraps [StoredAudio]; on failure wraps a [SynthesisException] variant.
 */
typealias SynthesisResult = Result<StoredAudio>

/**
 * Application use case that validates input, runs the TTS pipeline, and stores the result.
 *
 * Validates per-turn text length, speed range, and format before delegating to
 * [TtsService] for synthesis and [AudioStorage] for persistence.
 */
class SynthesizeSpeechUseCase(private val ttsService: TtsService, private val audioStorage: AudioStorage) {
    /**
     * Validate inputs, run the TTS pipeline, and store the resulting audio.
     *
     * @param turns dialogue turns to synthesize, each with a voice and text
     * @param speed playback speed multiplier (defaults to [Constants.DEFAULT_SPEED])
     * @param format output audio format name ("wav" or "mp3")
     * @return [SynthesisResult] wrapping [StoredAudio] on success or a [SynthesisException] on failure
     */
    suspend fun execute(
        turns: List<DialogueTurnInput>,
        speed: Double = Constants.DEFAULT_SPEED,
        format: String = Constants.DEFAULT_FORMAT,
    ): SynthesisResult = try {
        val speechRate = validateSpeed(speed)
        val audioFormat = parseAudioFormat(format)
        val dialogueTurns = buildDialogueTurns(turns)
        val audioChunk = ttsService.synthesizeDialogue(dialogueTurns, speechRate, audioFormat).getOrThrow()
        Result.success(storeAudio(audioChunk.data, audioFormat, dialogueTurns.first().voice))
    } catch (e: SynthesisException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(SynthesisException.InferenceFailed(e.message ?: "Unknown"))
    }

    private fun parseAudioFormat(format: String): AudioFormat = when (format) {
        "mp3" -> AudioFormat.MP3(SampleRate(Constants.SAMPLE_RATE))
        else -> AudioFormat.WAV(SampleRate(Constants.SAMPLE_RATE))
    }

    private fun validateSpeed(speed: Double): SpeechRate = try {
        SpeechRate(speed)
    } catch (_: IllegalArgumentException) {
        throw SynthesisException.SpeedOutOfRange(speed)
    }

    private fun buildDialogueTurns(turns: List<DialogueTurnInput>): List<DialogueTurn> {
        if (turns.isEmpty()) throw SynthesisException.DialogueEmpty()
        return turns.map { validateAndMapTurn(it) }
    }

    private fun validateAndMapTurn(turn: DialogueTurnInput): DialogueTurn {
        if (turn.text.length > Constants.MAX_TEXT_LENGTH) {
            throw SynthesisException.TextTooLong(turn.text.length, Constants.MAX_TEXT_LENGTH)
        }
        return try {
            DialogueTurn(voice = parseVoiceSpec(turn.voice), text = turn.text)
        } catch (e: IllegalArgumentException) {
            throw SynthesisException.InvalidInput(e.message ?: "Invalid turn input")
        }
    }

    private suspend fun storeAudio(data: ByteArray, format: AudioFormat, voice: VoiceSpec): StoredAudio = try {
        audioStorage.store(data, format, voice)
    } catch (e: Exception) {
        throw SynthesisException.StorageFailed(e.message ?: "Failed to store audio").initCause(e)
    }

    /**
     * Parse a voice specification string into a [VoiceSpec].
     *
     * Supports single voices (e.g. "af_heart") and blended voices
     * (e.g. "af_heart:0.6+bf_emma:0.4").
     *
     * @param voice the voice specification string
     * @return parsed [VoiceSpec] instance
     */
    private fun parseVoiceSpec(voice: String): VoiceSpec {
        if (!voice.contains('+')) {
            return VoiceSpec.SingleVoice(VoiceId(voice))
        }
        val components =
            voice.split('+').map { component ->
                val parts = component.split(':')
                require(parts.size == 2) { "Blend component must be 'voiceId:weight', got '$component'" }
                val voiceId = VoiceId(parts[0])
                val weight = VoiceBlendWeight(parts[1].toFloat())
                voiceId to weight
            }
        return VoiceSpec.BlendedVoice(components)
    }
}
