// :core:llama — offline GGUF LLM engine through llama.cpp.
// libllama_jni.so is vendored (jniLibs/arm64-v8a, built on-device 2026-10-09 from
// upstream llama.cpp). Depends only on :core:llm for the LlmEngine contract.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.saeed.minikeys.core.llama"
    compileSdk = 35
    buildToolsVersion = "35.0.1"
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core:llm"))
    testImplementation(libs.junit)
}
