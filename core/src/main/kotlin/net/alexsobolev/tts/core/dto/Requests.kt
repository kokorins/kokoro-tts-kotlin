package net.alexsobolev.tts.core.dto

import kotlinx.serialization.Serializable
import net.alexsobolev.tts.domain.Constants

@Serializable
data class DialogueRequest(
    val turns: List<DialogueTurnInput>,
    val speed: Double = Constants.DEFAULT_SPEED,
    val format: String = Constants.DEFAULT_FORMAT,
)

@Serializable
data class DialogueTurnInput(val voice: String, val text: String)

@Serializable
data class SynthesisRequest(
    val text: String,
    val voice: String = Constants.DEFAULT_VOICE_ID,
    val speed: Double = Constants.DEFAULT_SPEED,
    val format: String = Constants.DEFAULT_FORMAT,
)
