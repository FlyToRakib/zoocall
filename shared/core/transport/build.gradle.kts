plugins {
    id("zoocall.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core.model)
            api(projects.shared.core.protocol)
            api(projects.shared.core.crypto)
            implementation(libs.ktor.network)
        }
        commonTest.dependencies {
            implementation(libs.turbine)
        }
    }
}
