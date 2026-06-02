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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tally"
include(":core-math")
include(":keyboard-engine")
include(":layouts")
include(":prediction")
include(":emoji")
include(":feature-glue")
include(":ime")
include(":overlay")
include(":app")
include(":design-system")
include(":build-checks")
