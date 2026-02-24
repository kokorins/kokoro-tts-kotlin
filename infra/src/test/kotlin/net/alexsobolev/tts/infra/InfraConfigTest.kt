package net.alexsobolev.tts.infra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InfraConfigTest {
    @Test
    fun `create infra config with AWS`() {
        val aws = AwsConfig(region = "eu-central-1", bucket = "my-bucket")
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
                aws = aws,
                storagePrefix = "tts-audio",
                fixesDictPath = "/path/to/fixes.json",
            )

        assertEquals("/path/to/config.json", config.tokenizerConfigPath)
        assertEquals("/path/to/voices.bin", config.voicesPath)
        assertEquals(aws, config.aws)
        assertEquals("eu-central-1", config.aws?.region)
        assertEquals("my-bucket", config.aws?.bucket)
        assertEquals("tts-audio", config.storagePrefix)
    }

    @Test
    fun `create infra config without AWS uses local storage`() {
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
                aws = null,
                storagePrefix = "tts-audio",
                fixesDictPath = "/path/to/fixes.json",
            )

        assertNull(config.aws)
        assertEquals("tts-audio", config.storagePrefix)
    }

    @Test
    fun `infra config equality`() {
        val aws = AwsConfig("i", "j")
        val config1 = InfraConfig("a", "b", "c", "d", "e", "f", "g", "h", aws, "k", "l")
        val config2 = InfraConfig("a", "b", "c", "d", "e", "f", "g", "h", aws, "k", "l")

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
    }
}
