// :perception — "what is in front of the user, and what can be done there".
//
// This is FocusMap's proven perception layer, lifted into MiniKeys so the keyboard can offer the
// current activity's functions as keys. It contains no UI of its own: it reads the accessibility
// tree, turns it into runnable functions, and publishes them through [ActivityShortcuts].
//
// Pure-lawyer note on the split: everything that can be reasoned about without a device lives in the
// `uimap` package (no Android types, JVM-tested), everything that needs the framework lives here.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.saeed.minikeys.perception"
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
    // MiniKeysLog — the app's single diagnostic channel.
    api(project(":core:settings"))
    testImplementation(libs.junit)
}