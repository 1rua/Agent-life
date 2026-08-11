import org.gradle.api.initialization.resolve.RepositoriesMode

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

rootProject.name = "agent-life-android"
include(
    ":app",
    ":assistant-holder",
    ":artifact-ports",
    ":capability-ports",
    ":core-model",
    ":control-ports",
    ":policy-engine",
    ":notification-collector",
    ":tailnet-core",
    ":transport",
    ":encrypted-store",
)
