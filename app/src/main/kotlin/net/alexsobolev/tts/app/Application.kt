package net.alexsobolev.tts.app

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import net.alexsobolev.tts.app.http.configureRouting
import net.alexsobolev.tts.app.http.configureStatusPages

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureHTTP()
    configureSerialization()
    configureFrameworks()
    configureStatusPages()
    configureLogging()
    configureRouting()
}
