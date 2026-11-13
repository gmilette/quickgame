plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.greenopal.quickgames.server.MainKt")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":games:tictactoe"))
    implementation(platform(libs.google.cloud.bom))
    implementation(libs.google.cloud.firestore)
    testImplementation(kotlin("test"))
}
