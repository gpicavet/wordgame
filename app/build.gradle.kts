import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase is configured by app/google-services.json (from the Firebase console, not committed).
// Without it the app builds and runs, only without push notifications.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

// Upload key for Google Play (secrets/, not committed): storeFile, storePassword, keyAlias, keyPassword.
// Without it, release builds are unsigned (see README, "Publication").
val signing = rootProject.file("secrets/keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "app.wordgame.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.wordgame.android"
        minSdk = 26
        targetSdk = 36
        // Sent to the server with every request (X-App-Version): raise it with each release, and the server's
        // WORDGAME_MIN_APP_VERSION when older versions can no longer follow its API.
        versionCode = 2
        versionName = "0.2.0"
        // The VPS. For a local server: -PapiUrl=http://10.0.2.2:8080 (emulator) or http://<Mac IP>:8080 (phone).
        // "Sign in with Google": the Web OAuth client id of the Google Cloud project, the server's
        // WORDGAME_GOOGLE_CLIENT_ID (-PgoogleClientId=… or gradle.properties). Empty: the buttons are hidden.
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${project.findProperty("googleClientId") ?: ""}\"")
        buildConfigField("String", "API_URL", "\"${project.findProperty("apiUrl") ?: "https://vps-0483f9f2.vps.ovh.ca"}\"")
    }

    signingConfigs {
        if (signing != null) create("upload") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing != null) signingConfig = signingConfigs.getByName("upload")
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
    // Learned weights of the expert bot (trainer module).
    sourceSets["main"].assets.srcDir(rootProject.file("models"))
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

    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
}
