// :core:llm — OpenAI-compatible LLM client + encrypted API-key vault.
// Zero third-party dependencies on purpose (platform HttpURLConnection + org.json + Keystore),
// so it can be dropped into any app of the modular project.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.saeed.minikeys.core.llm"
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
    testImplementation(libs.junit)
}