pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "SanpoGuide"
// The platform (ADR-003): core libraries (plain JVM) and Android kits.
include(":platform:core-format")
include(":platform:core-prompt")
include(":platform:kit-voice")
include(":platform:kit-llm")
include(":platform:kit-geo")
include(":platform:kit-mood")
// SanpoGuide: the app and its prompt pack.
include(":apps:sanpoguide:app")
include(":apps:sanpoguide:prompts")
