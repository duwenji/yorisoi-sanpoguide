// The mood (time of day, season, sky, place) and what follows from it: the screen's colors and
// type, the scene drawn over a walk, and which background sound plays (RFC-002). Used by the apps
// in this repository; not published (RFC-002 R-11).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.duwenji.michizure.kit.mood"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The mood reads the weather and nearby spots (kit-geo) and picks a sound (kit-voice) the way
    // a channel asks (SoundChoice in core-format).
    api(project(":platform:kit-geo"))
    api(project(":platform:kit-voice"))
    api(project(":platform:core-format"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
