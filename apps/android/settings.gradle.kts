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

rootProject.name = "LazyEngFamily"
include(":app")
include(":spikes:secure-storage")
include(":spikes:saf-media3")
include(
    ":core:common",
    ":core:model",
    ":core:designsystem",
    ":core:database",
    ":core:datastore",
    ":core:security",
    ":core:media",
    ":core:network",
    ":core:testing",
    ":feature:onboarding",
    ":feature:profiles",
    ":feature:home",
    ":feature:library",
    ":feature:importmedia",
    ":feature:player",
    ":feature:dictionary",
    ":feature:vocabulary",
    ":feature:progress",
    ":feature:parent",
)
