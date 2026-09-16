plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll("kotlin.time.ExperimentalTime", "kotlin.uuid.ExperimentalUuidApi")
    }
}

application {
    mainClass.set("app.zoocall.cli.MainKt")
    applicationName = "zoocall-cli"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation(projects.shared.core.app)
    implementation(libs.kotlinx.coroutines.core)
}
