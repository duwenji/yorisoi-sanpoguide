import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// SanpoGuide's prompt pack (RFC-002 R-4): the app's prompt files, its built-in channels, how a
// channel's slots go into its prompts and the review samples. Plain JVM so the channel
// management system renders exactly the prompts the app sends (ADR-003 E-18).
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "io.github.duwenji.yorisoi.sanpoguide"
version = providers.gradleProperty("sanpoguidePromptsVersion").get()

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
    api(libs.yorisoi.core.format)
    api(libs.yorisoi.core.prompt)

    testImplementation(libs.junit)
    // core-format reads JSON with org.json, which it leaves to its users.
    testImplementation(libs.json)
}

// A jar (or the APK) can't list a resource folder, so the built-in channels come with a list of
// their files (BuiltInChannels.INDEX).
val channelsDir = layout.projectDirectory.dir("src/main/resources/sanpoguide/channels")
val channelIndexDir = layout.buildDirectory.dir("generated/channel-index")
val channelIndex by tasks.registering {
    inputs.dir(channelsDir)
    outputs.dir(channelIndexDir)
    doLast {
        val root = channelsDir.asFile
        val paths = root.walkTopDown().filter { it.isFile && it.name != "index.txt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
        val out = channelIndexDir.get().file("sanpoguide/channels/index.txt").asFile
        out.parentFile.mkdirs()
        out.writeText(paths.joinToString("\n", postfix = "\n"))
    }
}
sourceSets.main {
    resources.srcDir(channelIndex)
}

// Published to GitHub Packages for the channel management system (RFC-002 R-12, RFC-004 R-5).
// The yorisoi version it depends on must be published first. Users add org.json themselves.
publishing {
    publications {
        create<MavenPublication>("prompts") {
            artifactId = "prompts"
            from(components["java"])
            pom {
                name.set("SanpoGuide prompts")
                description.set(
                    "SanpoGuide's prompt pack: its prompts, built-in channels, slot assembly and review samples. " +
                        "Requires org.json on the classpath.",
                )
                url.set("https://github.com/duwenji/yorisoi-sanpoguide")
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/duwenji/yorisoi-sanpoguide")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
