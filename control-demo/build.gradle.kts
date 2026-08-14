plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "audio.soniqo.speech.control"
    compileSdk = 35

    defaultConfig {
        applicationId = "audio.soniqo.speech.control"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("VERSION_CODE")?.toString()?.toIntOrNull() ?: 1)
        versionName = (findProperty("VERSION_NAME")?.toString() ?: "dev")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("SIGNING_KEYSTORE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (System.getenv("SIGNING_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Use this checkout directly so speech, including Pocket streaming,
    // is always exercised through the local SDK under development.
    implementation(project(":sdk"))

    // FunctionGemma runs in the app rather than the SDK, keeping LiteRT-LM
    // out of the published speech artifact.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    // LiteRT-LM's Kotlin bindings are compiled against the interface-default
    // layout introduced in coroutines 1.11.0. Its published POM still asks
    // for 1.9.0, which can terminate the app with NoSuchMethodError when a
    // binding callback completes (google-ai-edge/LiteRT-LM#2812).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
