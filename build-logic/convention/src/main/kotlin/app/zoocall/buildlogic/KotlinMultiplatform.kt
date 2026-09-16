package app.zoocall.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.intVersion(alias: String): Int =
    findVersion(alias).get().requiredVersion.toInt()

internal fun Project.configureKmpLibrary() {
    val catalog = libs
    extensions.configure<KotlinMultiplatformExtension> {
        jvmToolchain(21)

        extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
            namespace = "app.zoocall" + path.replace(':', '.').replace('-', '_')
            compileSdk = catalog.intVersion("android-compileSdk")
            minSdk = catalog.intVersion("android-minSdk")
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            withHostTest {}
        }

        jvm("desktop") {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        }

        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xexpect-actual-classes",
                "-opt-in=kotlin.uuid.ExperimentalUuidApi",
                "-opt-in=kotlin.time.ExperimentalTime",
            )
        }

        sourceSets.getByName("commonTest").dependencies {
            implementation(kotlin("test"))
            implementation(catalog.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
