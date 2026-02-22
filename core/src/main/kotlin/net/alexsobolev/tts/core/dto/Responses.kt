package net.alexsobolev.tts.core.dto

import kotlinx.serialization.Serializable

@Serializable
data class SynthesisResponse(
    val url: String,
    val key: String,
    val expiresInSeconds: Long,
    val sizeBytes: Long,
    val format: String,
    val voice: String,
)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class Voice(val id: String, val language: String)

@Serializable
data class VoicesResponse(val voices: List<Voice>)
