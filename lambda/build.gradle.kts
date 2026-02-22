plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":infra"))
    implementation(libs.aws.lambda.core)
    implementation(libs.aws.lambda.events)
    implementation(libs.aws.lambda.ric)
    implementation(libs.aws.s3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.core)
    implementation(libs.logback.classic)

    testImplementation(libs.koin.core)
}

tasks.shadowJar {
    archiveBaseName.set("tts-lambda")
    archiveClassifier.set("")
    mergeServiceFiles()
}
