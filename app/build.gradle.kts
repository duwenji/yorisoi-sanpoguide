import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing keys live outside Git, in keystore.properties at the project root
// (storeFile, storePassword, keyAlias, keyPassword). Without it the release APK is unsigned.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }

android {
    namespace = "io.github.duwenji.yorisoi.sanpoguide"
    compileSdk = 36

    defaultConfig {
        // The Google Play ID: fixed once uploaded. The namespace and the Kotlin packages use the same name.
        applicationId = "io.github.duwenji.yorisoi.sanpoguide"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.8.0"

        // The operator's AI (platform API-AI-RELAY): every build uses the development relay until
        // production's is deployed.
        buildConfigField("String", "AI_RELAY_URL", "\"https://teevxbmiaj.execute-api.ap-northeast-1.amazonaws.com/api/\"")
        // The Google Cloud project linked in Play Console for Play Integrity; 0 sends no token (the
        // development relay doesn't ask for one).
        buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "0L")
        // The operator's spot tiles (platform API-SPOT-TILES ST-1): every build uses the development
        // ones until production's are deployed.
        buildConfigField("String", "SPOT_TILES_URL", "\"https://ds73vvo5wv45e.cloudfront.net/\"")
        // The studio (platform API-STUDIO ST-1): every build uses the development studio until
        // production's is deployed.
        buildConfigField("String", "STUDIO_URL", "\"https://x568icz881.execute-api.ap-northeast-1.amazonaws.com/api/\"")
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // A dependency pulls in a Fragment older than 1.3, too old for the ActivityResult API
    // (lint fails release builds with InvalidFragmentVersionForActivityResult).
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    // Searching ahead along the frequent ways, on Wi-Fi and between walks (platform REQ-FREQUENT-WAYS D-9).
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.play.services.location)
    implementation(libs.osmdroid.android)
    implementation(libs.okhttp)
    implementation(libs.anthropic.java)
    implementation(project(":prompts"))
    implementation(libs.yorisoi.kit.voice)
    implementation(libs.yorisoi.kit.llm)
    implementation(libs.yorisoi.kit.geo)
    implementation(libs.yorisoi.kit.mood)
    // The bar on top of the screens and the launcher icon's green, shared with CycleGuide.
    implementation(libs.yorisoi.kit.ui)
    implementation(libs.yorisoi.kit.companion)
    implementation(libs.yorisoi.kit.channel)
    // The studio: notes and placements near the walk (platform REQ-STUDIO, kit-studio).
    implementation(libs.yorisoi.kit.studio)
    // Ed25519 from Android 8 (the platform has it only from 13): third-party channels' signatures
    // and later API-001.
    implementation(libs.tink.android)
    // Test tickets' QR codes (API-002): Google's scanner UI, so the app needs no camera permission.
    implementation(libs.play.services.code.scanner)
    // The operator's AI: proves to the relay that this is the app from Play (API-AI-RELAY AR-3).

    testImplementation(libs.junit)
    // The real org.json; Android's is a stub in local unit tests.
    testImplementation(libs.json)
}
