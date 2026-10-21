rootProject.name = "wordgame"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":engine", ":protocol", ":dict-builder", ":server")

// The Android app needs an Android SDK; engine and server build without one.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null || file("local.properties").exists()
if (hasAndroidSdk) include(":app")
