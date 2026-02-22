package net.alexsobolev.tts.infra

import kotlin.test.Test
import kotlin.test.assertEquals

class InfraConfigTest {
    @Test
    fun `create infra config`() {
        // given
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
            )

        // then
        assertEquals("/path/to/config.json", config.tokenizerConfigPath)
        assertEquals("/path/to/voices.bin", config.voicesPath)
        assertEquals("eu-central-1", config.awsRegion)
        assertEquals("my-bucket", config.s3Bucket)
        assertEquals("tts-audio", config.storagePrefix)
    }

    @Test
    fun `infra config equality`() {
        // given
        val config1 = InfraConfig("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l")
        val config2 = InfraConfig("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l")

        // then
        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
    }
}
