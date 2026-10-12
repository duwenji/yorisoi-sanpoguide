import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Deciding what to say now: rules in order, each behind gates the app opens and closes
// (RFC-003 D-10〜D-14). Plain JVM: the engine holds no values, the app's rules and situation do.
// Used by the apps in this repository; not published (RFC-002 R-11).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
