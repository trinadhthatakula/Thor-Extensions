pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Local cross-repo development against the Asgard UI library (until it syncs to Central, or for
// editing both together). Set `asgardDir` in local.properties / ~/.gradle/gradle.properties.
// Asgard is consumed compileOnly, so it is only needed at compile time here.
val asgardDir = providers.gradleProperty("asgardDir").orNull
if (asgardDir != null) {
    includeBuild(asgardDir)
}

rootProject.name = "thor-antivirus-extension"
include(":app")

