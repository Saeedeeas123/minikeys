// :core:settings — persistence + settings model. No Android UI, no third-party deps.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.saeed.minikeys.core.settings"
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
