rootProject.name = "kokoro-tts-kotlin"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":domain", ":core", ":infra", ":app", ":lambda")
