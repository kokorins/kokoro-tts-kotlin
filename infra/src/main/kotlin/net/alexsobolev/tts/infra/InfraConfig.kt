package net.alexsobolev.tts.infra

/**
 * File paths and AWS settings needed to wire up the infrastructure module adapters.
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
    val awsRegion: String,
    val s3Bucket: String,
    val storagePrefix: String,
    val fixesDictPath: String,
)
