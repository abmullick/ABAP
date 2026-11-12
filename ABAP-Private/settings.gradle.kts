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

rootProject.name = "ABAP Private"
include(
    ":app",
    ":core:common",
    ":core:identity",
    ":core:crypto",
    ":core:protocol",
    ":core:network",
    ":core:storage",
    ":core:security",
    ":feature:pairing",
    ":feature:chat",
    ":feature:calling",
    ":feature:connection",
    ":feature:diagnostics",
    ":feature:settings",
    ":service:foreground",
    ":service:audio"
)
