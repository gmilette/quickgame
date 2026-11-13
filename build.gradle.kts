plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
}

val checkModuleDependencies = tasks.register("checkModuleDependencies") {
    val declared = subprojects.associate { sub ->
        sub.path to sub.configurations.flatMap { config ->
            config.dependencies.withType(ProjectDependency::class.java).map { it.path }
        }.toSet()
    }
    doLast {
        val violations = buildList {
            declared.forEach { (path, deps) ->
                deps.forEach { dep ->
                    if (path.startsWith(":games:") && dep.startsWith(":games:") && dep != path) {
                        val pathGame = path.removePrefix(":games:").removeSuffix("-ui")
                        val depGame = dep.removePrefix(":games:").removeSuffix("-ui")
                        if (pathGame != depGame) {
                            add("$path -> $dep (games modules must not depend on each other)")
                        }
                    }
                    if (path.startsWith(":core:") && (dep.startsWith(":games:") || dep.startsWith(":app:"))) {
                        add("$path -> $dep (core modules must not depend on games or app modules)")
                    }
                }
            }
        }
        require(violations.isEmpty()) {
            "Module dependency rules violated:\n" + violations.joinToString("\n")
        }
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(checkModuleDependencies)
    }
}
