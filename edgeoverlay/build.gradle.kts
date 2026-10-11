// :edgeoverlay — the always-on EDGE SHORTCUT SURFACE.
// Not an IME: two separate TYPE_APPLICATION_OVERLAY windows (one per screen edge) so the middle of
// the screen is never covered by a window and touches reach the app behind it natively.
// Depends on :core:settings for its persisted settings and on :keyboard for the key data types.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.saeed.minikeys.edgeoverlay"
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
    api(project(":core:settings"))
    implementation(libs.androidx.core.ktx)
    implementation(project(":keyboard"))
}
