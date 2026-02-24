package net.alexsobolev.tts.infra

import aws.sdk.kotlin.services.s3.S3Client
import net.alexsobolev.tts.core.api.AudioEncoder
import net.alexsobolev.tts.core.api.AudioStorage
import net.alexsobolev.tts.core.api.InferenceEngine
import net.alexsobolev.tts.core.api.PhonemeGenerator
import net.alexsobolev.tts.core.api.VoiceRepository
import net.alexsobolev.tts.infra.encoder.LocalAudioEncoder
import net.alexsobolev.tts.infra.g2p.EnglishPhonemeGenerator
import net.alexsobolev.tts.infra.g2p.LetterPhonemeConverter
import net.alexsobolev.tts.infra.g2p.LetterPhonemeRules
import net.alexsobolev.tts.infra.g2p.NumberExpander
import net.alexsobolev.tts.infra.g2p.OpenNlpPosTagger
import net.alexsobolev.tts.infra.g2p.PosAwareLexicon
import net.alexsobolev.tts.infra.g2p.PosTagger
import net.alexsobolev.tts.infra.inference.KokoroTokenizer
import net.alexsobolev.tts.infra.inference.NpzVoiceRepository
import net.alexsobolev.tts.infra.inference.OnnxKokoroEngine
import net.alexsobolev.tts.infra.storage.LocalFileAudioStorage
import net.alexsobolev.tts.infra.storage.S3AudioStorage
import org.koin.dsl.module

fun infraModule(config: InfraConfig) = module {
    single { config }

    single { KokoroTokenizer(config.tokenizerConfigPath) }

    single<VoiceRepository> {
        NpzVoiceRepository(config.voicesPath)
    }

    single { LetterPhonemeRules() }

    single { LetterPhonemeConverter(get()) }

    single { NumberExpander() }

    single {
        PosAwareLexicon(
            config.goldDictPath,
            config.silverDictPath,
            config.gbGoldDictPath,
            config.gbSilverDictPath,
            config.fixesDictPath,
        )
    }

    single<PosTagger> { OpenNlpPosTagger(config.posModelPath) }

    single<PhonemeGenerator> {
        EnglishPhonemeGenerator(
            lexicon = get(),
            fallback = get(),
            numberExpander = get(),
            posTagger = get(),
        )
    }

    single<InferenceEngine> {
        OnnxKokoroEngine(
            modelPath = config.onnxModelPath,
            voiceRepo = get(),
            tokenizer = get(),
        )
    }

    single<AudioEncoder> { LocalAudioEncoder() }

    if (config.storageMode == "s3") {
        single {
            S3Client {
                region = config.awsRegion
                retryStrategy {
                    maxAttempts = 3
                }
            }
        }

        single<AudioStorage> {
            S3AudioStorage(
                s3 = get(),
                bucketName = config.s3Bucket,
                region = config.awsRegion,
                storagePrefix = config.storagePrefix,
            )
        }
    } else {
        single<AudioStorage> {
            LocalFileAudioStorage(
                outputDir = config.localOutputDir,
                baseUrl = config.baseUrl,
            )
        }
    }
}
