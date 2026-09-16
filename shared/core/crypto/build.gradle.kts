plugins {
    id("zoocall.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core.model)
            implementation(libs.libsodium.bindings)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
