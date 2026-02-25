package net.alexsobolev.tts.infra

/**
 * File paths and storage settings needed to wire up the infrastructure module adapters.
 * When [storageMode] is "s3", [awsRegion] and [s3Bucket] are used; otherwise [localOutputDir] and [baseUrl] are used.
 */
data class InfraConfig(
    val tokenizerConfigPath: String,
    val voicesPath: String,
    val goldDictPath: String,
    val silverDictPath: String,
    val gbGoldDictPath: String,
    val gbSilverDictPath: String,
    val posModelPath: String,
    val onnxModelPath: String,
    val awsRegion: String = "",
    val s3Bucket: String = "",
    val storagePrefix: String = "tts-audio",
    val fixesDictPath: String,
    val storageMode: String = "local",
    val localOutputDir: String = "output",
    val baseUrl: String = "http://localhost:8080",
)
