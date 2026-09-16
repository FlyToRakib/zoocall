import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

plugins {
    id("zoocall.kmp.compose")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
        androidResources { enable = true }
    }

    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core.app)
            api(projects.shared.media)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            api(libs.cmp.material3)
            implementation(libs.cmp.material.icons.extended)
            implementation(libs.cmp.lifecycle.viewmodel.compose)
            implementation(libs.cmp.lifecycle.runtime.compose)
            // Shells pass a NavHostController into ZoocallApp for deep links.
            api(libs.cmp.navigation.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.zxing.core)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.zxing.core)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.resources {
    packageOfResClass = "app.zoocall.ui.resources"
    publicResClass = false
    generateResClass = always
}
