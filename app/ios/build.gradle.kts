import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("native.cocoapods")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    cocoapods {
        name = "Shared"
        version = "1.0.0"
        summary = "QuickGames shared iOS framework"
        homepage = "https://example.com/quickgames"
        ios.deploymentTarget = "15.0"
        podfile = project.file("iosApp/Podfile")

        framework {
            baseName = "Shared"
            isStatic = true
            export(project(":core:model"))
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:engine"))
            implementation(project(":core:firebase"))
            implementation(project(":core:ui"))
            implementation(project(":games:tictactoe"))
            implementation(project(":games:tictactoe-ui"))
            implementation(project(":games:rps"))
            implementation(project(":games:rps-ui"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
    }
}
