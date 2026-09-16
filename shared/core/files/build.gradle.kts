plugins {
    id("zoocall.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core.transport)
            api(projects.shared.core.store)
            api(libs.okio)
        }
        getByName("desktopTest").dependencies {
            implementation(projects.shared.core.chat)
        }
    }
}
