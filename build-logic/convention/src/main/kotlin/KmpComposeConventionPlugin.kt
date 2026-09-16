import org.gradle.api.Plugin
import org.gradle.api.Project

/** Adds Compose Multiplatform on top of [KmpLibraryConventionPlugin]. */
class KmpComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("zoocall.kmp.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.compose")
    }
}
