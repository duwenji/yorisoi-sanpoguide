import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The prompt template engine (a small subset of Mustache). Plain JVM, no dependencies, so the
// apps and the channel management system render prompts the same way (ADR-003 E-3).
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
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("corePrompt") {
            artifactId = "core-prompt"
            from(components["java"])
            pom {
                name.set("michizure core-prompt")
                description.set("Prompt templates: the subset of Mustache the apps' prompt files are written in.")
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
