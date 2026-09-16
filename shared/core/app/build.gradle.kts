plugins {
    id("zoocall.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core.call)
            api(projects.shared.core.chat)
            api(projects.shared.core.files)
            api(projects.shared.core.discovery)
            api(projects.shared.core.store)
            api(projects.shared.media)
            api(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }
    }
}
