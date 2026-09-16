plugins {
    id("zoocall.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core.transport)
            api(projects.shared.core.store)
        }
    }
}
