package net.alexsobolev.tts.app

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.sse.SSE
import net.alexsobolev.tts.core.coreModule
import net.alexsobolev.tts.infra.AwsConfig
import net.alexsobolev.tts.infra.InfraConfig
import net.alexsobolev.tts.infra.infraModule
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import org.slf4j.event.Level

fun Application.configureFrameworks() {
    val aws = run {
        val region = try {
            environment.config.property("tts.aws.region").getString().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val bucket = try {
            environment.config.property("tts.aws.s3Bucket").getString().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        if (region != null && bucket != null) AwsConfig(region = region, bucket = bucket) else null
    }
    val config =
        InfraConfig(
            tokenizerConfigPath = environment.config.property("tts.tokenizer.configPath").getString(),
            voicesPath = environment.config.property("tts.voices.path").getString(),
            goldDictPath = environment.config.property("tts.phonemizer.goldDictPath").getString(),
            silverDictPath = environment.config.property("tts.phonemizer.silverDictPath").getString(),
            gbGoldDictPath = environment.config.property("tts.phonemizer.gbGoldDictPath").getString(),
            gbSilverDictPath = environment.config.property("tts.phonemizer.gbSilverDictPath").getString(),
            posModelPath = environment.config.property("tts.pos.modelPath").getString(),
            onnxModelPath = environment.config.property("tts.model.onnxPath").getString(),
            aws = aws,
            storagePrefix = environment.config.property("tts.storage.prefix").getString(),
            fixesDictPath = environment.config.property("tts.phonemizer.fixesDictPath").getString(),
            storageMode = environment.config.property("tts.storage.mode").getString(),
            localOutputDir = environment.config.property("tts.storage.localOutputDir").getString(),
            baseUrl = environment.config.property("tts.storage.baseUrl").getString(),
        )

    install(Koin) {
        slf4jLogger()
        modules(infraModule(config), coreModule())
    }
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
}

fun Application.configureHTTP() {
    install(Compression)
    install(SSE)
}

fun Application.configureLogging() {
    install(CallLogging) {
        level = Level.INFO
    }
}
