rootProject.name = "zoocall"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            mavenContent {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

include(
    ":shared:core:model",
    ":shared:core:protocol",
    ":shared:core:crypto",
    ":shared:core:transport",
    ":shared:core:discovery",
    ":shared:core:call",
    ":shared:core:store",
    ":shared:core:chat",
    ":shared:core:files",
    ":shared:core:app",
    ":shared:media",
    ":shared:ui",
    ":android",
    ":desktop",
    ":tools:cli",
)
