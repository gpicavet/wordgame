plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase is configured by app/google-services.json (from the Firebase console, not committed).
// Without it the app builds and runs, only without push notifications.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "app.wordgame.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.wordgame.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The VPS. For a local server: -PapiUrl=http://10.0.2.2:8080 (emulator) or http://<Mac IP>:8080 (phone).
        buildConfigField("String", "API_URL", "\"${project.findProperty("apiUrl") ?: "https://vps-0483f9f2.vps.ovh.ca"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The DAWG built by :dict-builder, used for the live score preview.
    sourceSets["main"].assets.srcDir(rootProject.file("dictionaries/bin"))
    // Private word lists (not committed): bundled when present.
    sourceSets["main"].assets.srcDir(rootProject.file("dictionaries/private"))
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":protocol"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
}
