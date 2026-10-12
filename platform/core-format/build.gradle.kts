import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The channel and collection package formats (API-003, API-004) and the channel list (API-002):
// reading and checking packages, their signatures and the list. Plain JVM so the channel
// management system's review tools run exactly the checks the apps run (ADR-003 E-3, RFC-002 R-2).
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "io.github.duwenji.michizure"
version = providers.gradleProperty("michizureCoreVersion").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Android ships org.json; bundling the jar into the app would clash with it
    // (lint: DuplicatePlatformClasses). Other users add it themselves.
    compileOnly(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

// Published to GitHub Packages for the channel management system (RFC-002 R-11, R-12).
// Users add org.json themselves (see the dependency note above).
publishing {
    publications {
        create<MavenPublication>("coreFormat") {
            artifactId = "core-format"
            from(components["java"])
            pom {
                name.set("michizure core-format")
                description.set(
                    "Channel and collection package formats, their checks and the channel list (API-002/003/004). " +
                        "Requires org.json on the classpath.",
                )
                url.set("https://github.com/duwenji/SanpoGuide")
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/duwenji/SanpoGuide")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
