package net.alexsobolev.tts.infra

import kotlin.test.Test
import kotlin.test.assertEquals

class InfraConfigTest {
    @Test
    fun `create infra config with S3 storage mode`() {
        val config =
            InfraConfig(
                tokenizerConfigPath = "/path/to/config.json",
                voicesPath = "/path/to/voices.bin",
                goldDictPath = "/path/to/gold.json",
                silverDictPath = "/path/to/silver.json",
                gbGoldDictPath = "/path/to/gb_gold.json",
                gbSilverDictPath = "/path/to/gb_silver.json",
                posModelPath = "/path/to/pos-model.bin",
                onnxModelPath = "/path/to/model.onnx",
                awsRegion = "eu-central-1",
                s3Bucket = "my-bucket",
                storagePrefix = "tts-audio",
                fixesDictPath = "/path/to/fixes.json",
                storageMode = "s3",
            )

        assertEquals("/path/to/config.json", config.tokenizerConfigPath)
        assertEquals("/path/to/voices.bin", config.voicesPath)
        assertEquals("eu-central-1", config.awsRegion)
        assertEquals("my-bucket", config.s3Bucket)
        assertEquals("tts-audio", config.storagePrefix)
        assertEquals("s3", config.storageMode)
    }

    @Test
    fun `create infra config with local storage mode`() {
        val config =
            InfraConfig(
                tokenizerConfigPath = "/path/to/config.json",
                voicesPath = "/path/to/voices.bin",
                goldDictPath = "/path/to/gold.json",
                silverDictPath = "/path/to/silver.json",
                gbGoldDictPath = "/path/to/gb_gold.json",
                gbSilverDictPath = "/path/to/gb_silver.json",
                posModelPath = "/path/to/pos-model.bin",
                onnxModelPath = "/path/to/model.onnx",
                fixesDictPath = "/path/to/fixes.json",
                storageMode = "local",
                localOutputDir = "output",
                baseUrl = "http://localhost:8080",
            )

        assertEquals("tts-audio", config.storagePrefix)
        assertEquals("local", config.storageMode)
        assertEquals("output", config.localOutputDir)
    }

    @Test
    fun `infra config equality`() {
        val config1 =
            InfraConfig(
                tokenizerConfigPath = "a",
                voicesPath = "b",
                goldDictPath = "c",
                silverDictPath = "d",
                gbGoldDictPath = "e",
                gbSilverDictPath = "f",
                posModelPath = "g",
                onnxModelPath = "h",
                awsRegion = "i",
                s3Bucket = "j",
                storagePrefix = "k",
                fixesDictPath = "l",
            )
        val config2 =
            InfraConfig(
                tokenizerConfigPath = "a",
                voicesPath = "b",
                goldDictPath = "c",
                silverDictPath = "d",
                gbGoldDictPath = "e",
                gbSilverDictPath = "f",
                posModelPath = "g",
                onnxModelPath = "h",
                awsRegion = "i",
                s3Bucket = "j",
                storagePrefix = "k",
                fixesDictPath = "l",
            )

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
    }
}
