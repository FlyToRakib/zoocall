plugins {
    `kotlin-dsl`
}

group = "app.zoocall.buildlogic"

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.gradle.plugin.kotlin)
    compileOnly(libs.gradle.plugin.android)
    compileOnly(libs.gradle.plugin.compose)
    compileOnly(libs.gradle.plugin.compose.compiler)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "zoocall.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpCompose") {
            id = "zoocall.kmp.compose"
            implementationClass = "KmpComposeConventionPlugin"
        }
    }
}
