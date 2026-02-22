plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.onnxruntime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.jump3r)
    implementation(libs.aws.s3)
    api(libs.mcp.sdk.server)
    implementation(libs.opennlp.tools)
}
