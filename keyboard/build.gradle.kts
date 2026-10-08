// :keyboard — the IME itself (InputMethodService + the custom keyboard View).
// Pure framework APIs; depends only on :core:settings.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.saeed.minikeys.keyboard"
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
    api(project(":core:settings"))
}
