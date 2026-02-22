package net.alexsobolev.tts.core

import net.alexsobolev.tts.core.service.SentencePostProcessor
import net.alexsobolev.tts.core.service.TtsService
import net.alexsobolev.tts.core.service.TurnGapGenerator
import net.alexsobolev.tts.core.usecase.SynthesizeSpeechUseCase
import org.koin.dsl.module

fun coreModule() = module {
    single { TurnGapGenerator() }
    single { SentencePostProcessor() }
    single { TtsService(get(), get(), get(), get(), get()) }
    single { SynthesizeSpeechUseCase(get(), get()) }
}
