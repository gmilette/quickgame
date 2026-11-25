plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.greenopal.quickgames.app"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.greenopal.quickgames"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField(
            "String",
            "EMULATOR_HOST",
            "\"${project.findProperty("emulatorHost") ?: "10.0.2.2"}\""
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:firebase"))
    implementation(project(":core:ui"))
    implementation(project(":games:tictactoe"))
    implementation(project(":games:tictactoe-ui"))
    implementation(project(":games:rps"))
    implementation(project(":games:rps-ui"))
    implementation(project(":games:rpg"))
    implementation(project(":games:rpg-ui"))
    implementation(project(":games:hexstead"))
    implementation(project(":games:hexstead-ui"))
    implementation(project(":games:spiceroad"))
    implementation(project(":games:spiceroad-ui"))
    implementation(project(":games:squad"))
    implementation(project(":games:squad-ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.firebase.messaging)
}
