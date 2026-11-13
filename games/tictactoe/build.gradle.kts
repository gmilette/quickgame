plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    androidTarget()
    if (providers.gradleProperty("quickgames.ios").orNull == "true") {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:engine"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

android {
    namespace = "com.greenopal.quickgames.games.tictactoe"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("vectors.dir", rootProject.projectDir.resolve("test-vectors").absolutePath)
    System.getProperty("vectors.regenerate")?.let { systemProperty("vectors.regenerate", it) }
    (project.findProperty("vectors.regenerate") as String?)?.let { systemProperty("vectors.regenerate", it) }
}
