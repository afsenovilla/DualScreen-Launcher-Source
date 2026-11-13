pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "DualScreenLauncher"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":launcher")
include(":engine")
include(":secondscreen")
include(":core:common")
include(":core:model")
include(":core:designsystem")
include(":core:display")
