// :core:mediapipe — offline LLM engine (MediaPipe LLM Inference) + model file management.
// Depends on :core:llm for the shared LlmEngine contract. MediaPipe artifacts are already in the
// local Gradle cache (verified 2026-10-09) — zero downloads.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.saeed.minikeys.core.mediapipe"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:llm"))
    implementation(libs.mediapipe.tasks.genai)
    testImplementation(libs.junit)
}