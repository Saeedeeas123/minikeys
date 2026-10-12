pluginManagement {
    repositories {
        // Aliyun mirrors FIRST (dl.google.com is blocked on this device/network — verified)
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Aliyun mirrors FIRST (verified working in Iran network)
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}
rootProject.name = "MiniKeys"

// Modular layout: one Gradle module per concern; dependency flow is one-way
// :app -> :keyboard -> :perception -> :core:settings
include(":app")
include(":keyboard")
include(":perception")
include(":core:settings")
include(":core:llm")
include(":edgeoverlay")
