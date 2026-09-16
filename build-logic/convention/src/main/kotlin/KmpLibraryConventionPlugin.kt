import app.zoocall.buildlogic.configureKmpLibrary
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Kotlin Multiplatform library shared by the Android app and the Desktop app.
 *
 * Targets: `android` (AGP KMP library) and `jvm("desktop")`. The Android namespace is derived
 * from the Gradle path, e.g. `:shared:core:crypto` → `app.zoocall.shared.core.crypto`.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        configureKmpLibrary()
    }
}
