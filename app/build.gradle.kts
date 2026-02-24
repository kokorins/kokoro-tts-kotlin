plugins {
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

// Resolve data/* paths (config, model, dictionaries) from project root
tasks.named<org.gradle.api.tasks.JavaExec>("run").configure {
    workingDir = rootProject.projectDir
}

ktor {
    openApi {
        enabled = true
        codeInferenceEnabled = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":infra"))
    implementation(libs.aws.s3)

    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.openapi)
    implementation(libs.ktor.server.routing.openapi)
    implementation("io.ktor:ktor-server-sse:${libs.versions.ktor.get()}")
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)

    testImplementation(libs.ktor.server.test.host)
}
