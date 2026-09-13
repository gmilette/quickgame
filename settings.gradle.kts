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
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "quickgames"

include(":core:model")
include(":core:engine")
include(":core:firebase")
include(":core:ui")
include(":games:tictactoe")
include(":app:android")
if (providers.gradleProperty("quickgames.ios").orNull == "true") {
    include(":app:ios")
}
