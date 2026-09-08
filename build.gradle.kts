plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP's built-in Kotlin compiles each module, but AGP only asks for the KGP
    // it ships with. Declaring KGP here pins the compiler for the whole build
    // instead of leaving it to whichever version wins classpath resolution.
    // Two constraints fix the version:
    //   - LiteRT-LM publishes Kotlin 2.3 metadata, so the compiler must be 2.2+.
    //   - The compiler sets the metadata version of the published `audio.soniqo:speech`
    //     AAR, and a consumer can only read metadata one minor above its own
    //     compiler. Staying on 2.3.x keeps the AAR readable by consumers on
    //     Kotlin 2.2, which is what AGP's built-in Kotlin still defaults to.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.maven.publish) apply false
}
