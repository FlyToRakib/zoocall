plugins {
    id("zoocall.kmp.compose")
}

fun webrtcNativeClassifier(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val cpu = if (arch == "aarch64" || arch == "arm64") "aarch64" else "x86_64"
    return when {
        os.contains("win") -> "windows-$cpu"
        os.contains("mac") -> "macos-$cpu"
        else -> "linux-$cpu"
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.shared.core.model)
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
        }
        // Code shared by the two JVM-based targets (Android + desktop): the Opus voice-note codec.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.concentus)
            }
        }
        androidMain.get().dependsOn(jvmCommonMain)
        getByName("desktopMain").dependsOn(jvmCommonMain)
        getByName("desktopTest").dependencies {
            implementation(libs.concentus)
        }

        androidMain.dependencies {
            api(libs.webrtc.android)
            implementation(libs.kotlinx.coroutines.android)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.webrtc.java)
            // Native libwebrtc for the OS building the app (CI builds each OS on its own runner).
            implementation("dev.onvoid.webrtc:webrtc-java:${libs.versions.webrtc.java.get()}:${webrtcNativeClassifier()}")
        }
    }
}
