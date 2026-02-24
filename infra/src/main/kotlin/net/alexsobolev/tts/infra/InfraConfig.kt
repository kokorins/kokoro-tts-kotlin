package net.alexsobolev.tts.infra

/**
 * AWS-specific settings. When present, S3 storage is used; when null, local storage is used.
 */
data class AwsConfig(
    val region: String,
    val bucket: String,
)

/**
 * File paths and optional AWS settings needed to wire up the infrastructure module adapters.
 * When [aws] is null, [storagePrefix] is used as the base directory for local file storage.
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
<<<<<<< HEAD
    val awsRegion: String = "",
    val s3Bucket: String = "",
    val storagePrefix: String = "tts-audio",
=======
    val aws: AwsConfig?,
    val storagePrefix: String,
>>>>>>> 782d46b (Adding local storage, make gradle find proper jdk version)
    val fixesDictPath: String,
    val storageMode: String = "local",
    val localOutputDir: String = "output",
    val baseUrl: String = "http://localhost:8080",
)
