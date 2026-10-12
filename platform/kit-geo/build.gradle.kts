// Places, routes, photos, weather and the map (RFC-002). Used by the apps in this repository;
// not published (RFC-002 R-11).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.duwenji.michizure.kit.geo"
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
    // The spot kinds a search asks for (SpotKind).
    api(project(":platform:core-format"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.osmdroid.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    // The real org.json; Android's is a stub in local unit tests.
    testImplementation(libs.json)
}
