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
include(":games:tictactoe-ui")
include(":games:rps")
include(":games:rps-ui")
include(":games:rpg")
include(":games:rpg-ui")
include(":games:squad")
include(":games:squad-ui")
include(":server")
include(":app:android")
if (providers.gradleProperty("quickgames.ios").orNull == "true") {
    include(":app:ios")
}
