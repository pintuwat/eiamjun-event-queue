// A Gradle build of its own, nested inside the POS repo but not included by its settings file.
// The two apps share no code at build time: the printer layer was copied in rather than depended
// on, because the event till is meant to be changed on the morning of an event without any chance
// of breaking the shop's own till.
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

rootProject.name = "EiamJunEvent"
include(":app")
