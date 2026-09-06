plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // LiteRT-LM 0.16.0 publishes Kotlin 2.3 metadata, which requires the
    // Kotlin 2.2 compiler used by the full-pipeline Compose demo.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.maven.publish) apply false
}
