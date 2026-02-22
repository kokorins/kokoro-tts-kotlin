plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.kover)
}

group = "net.alexsobolev.tts"
version = "0.0.1"

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    configure<dev.detekt.gradle.extensions.DetektExtension> {
        config.setFrom(rootProject.files("detekt.yml"))
        buildUponDefaultConfig = true
    }

    dependencies {
        "testImplementation"(rootProject.libs.kotlin.test.junit)
        "testImplementation"(rootProject.libs.mockk)
        "testImplementation"(rootProject.libs.kotlinx.coroutines.test)
    }
}

dependencies {
    kover(project(":domain"))
    kover(project(":core"))
    kover(project(":infra"))
    kover(project(":app"))
    kover(project(":lambda"))
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "net.alexsobolev.tts.infra.inference.OnnxKokoroEngine*",
                    "net.alexsobolev.tts.app.ApplicationKt*",
                )
            }
        }
        verify {
            rule {
                minBound(85)
            }
        }
    }
}
