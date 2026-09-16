import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.shared.ui)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.cmp.material.icons.extended)
    implementation(libs.cmp.navigation.compose)
    implementation(libs.jna.platform)
}

val appVersion = "0.1.0"

compose.desktop {
    application {
        mainClass = "app.zoocall.desktop.MainKt"
        jvmArgs += listOf(
            "-Xmx512m",
            // Idle memory budget (docs/08 §5): G1 returns unused heap to the OS between calls.
            "-XX:+UseG1GC",
            "-XX:G1PeriodicGCInterval=30000",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:+UseStringDeduplication",
            "-Dzoocall.version=$appVersion",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Zoocall"
            packageVersion = appVersion
            description = "Calls and chat over your local Wi-Fi. No internet, no accounts."
            vendor = "Zoocall contributors"
            copyright = "MIT License"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.naming", "java.sql", "jdk.unsupported", "jdk.crypto.ec")

            windows {
                menuGroup = "Zoocall"
                perUserInstall = true
                shortcut = true
                dirChooser = false
                upgradeUuid = "6B0B4E7A-6C1B-4E0E-9C4D-2F7C1A9E5D31"
            }
            macOS {
                bundleID = "io.github.flytorakib.zoocall"
                appCategory = "public.app-category.social-networking"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Zoocall uses the microphone for calls.</string>
                        <key>NSCameraUsageDescription</key>
                        <string>Zoocall uses the camera for video calls.</string>
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>Zoocall finds and calls people on your local network.</string>
                        <key>NSBonjourServices</key>
                        <array><string>_zoocall._tcp</string></array>
                    """.trimIndent()
                }
            }
            linux {
                packageName = "zoocall"
            }
        }
    }
}
