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
include(":app")
include(":platform:core-format")
include(":platform:core-prompt")
include(":apps:sanpoguide:prompts")
include(":platform:kit-voice")
include(":platform:kit-llm")
