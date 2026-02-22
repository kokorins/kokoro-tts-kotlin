plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":domain"))
    api(libs.slf4j.api)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.core)
}
