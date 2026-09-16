package app.zoocall.desktop

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * OS integrations for the packaged desktop app. Only active in installed builds
 * (`jpackage.app-path` is set by the native launcher); `gradlew run` leaves the system untouched.
 */
object DesktopIntegration {
    private val os = System.getProperty("os.name").lowercase()
    val isWindows = os.contains("win")
    private val isMac = os.contains("mac")

    /** The installed launcher executable, or null when running from Gradle. */
    private val appPath: String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).exists() }

    /** Launch argument used for start at login: start hidden in the tray. */
    const val BACKGROUND_ARG = "--background"

    object StartAtLogin {
        private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        private const val VALUE = "Zoocall"

        val isSupported: Boolean get() = appPath != null && (isWindows || isMac || os.contains("linux"))

        fun isEnabled(): Boolean = runCatching {
            when {
                appPath == null -> false
                isWindows -> com.sun.jna.platform.win32.Advapi32Util.registryValueExists(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE)
                isMac -> macAgent().exists()
                else -> linuxAutostart().exists()
            }
        }.getOrDefault(false)

        /** Returns whether the change was applied. */
        fun setEnabled(enabled: Boolean): Boolean = runCatching {
            val exe = appPath ?: return false
            when {
                isWindows -> {
                    val hkcu = com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
                    if (enabled) {
                        com.sun.jna.platform.win32.Advapi32Util.registrySetStringValue(hkcu, RUN_KEY, VALUE, "\"$exe\" $BACKGROUND_ARG")
                    } else if (com.sun.jna.platform.win32.Advapi32Util.registryValueExists(hkcu, RUN_KEY, VALUE)) {
                        com.sun.jna.platform.win32.Advapi32Util.registryDeleteValue(hkcu, RUN_KEY, VALUE)
                    }
                }
                isMac -> {
                    val file = macAgent()
                    if (enabled) {
                        file.parentFile.mkdirs()
                        file.writeText(
                            """
                            <?xml version="1.0" encoding="UTF-8"?>
                            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                            <plist version="1.0"><dict>
                              <key>Label</key><string>io.github.flytorakib.zoocall</string>
                              <key>ProgramArguments</key><array><string>${exe.xmlEscaped()}</string><string>$BACKGROUND_ARG</string></array>
                              <key>RunAtLoad</key><true/>
                            </dict></plist>
                            """.trimIndent(),
                        )
                    } else {
                        file.delete()
                    }
                }
                else -> {
                    val file = linuxAutostart()
                    if (enabled) {
                        file.parentFile.mkdirs()
                        file.writeText("[Desktop Entry]\nType=Application\nName=Zoocall\nExec=\"${exe.replace("\"", "\\\"")}\" $BACKGROUND_ARG\nX-GNOME-Autostart-enabled=true\n")
                    } else {
                        file.delete()
                    }
                }
            }
            true
        }.getOrDefault(false)

        private fun macAgent() = File(System.getProperty("user.home"), "Library/LaunchAgents/io.github.flytorakib.zoocall.plist")

        private fun linuxAutostart(): File {
            val config = System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config"
            return File(config, "autostart/zoocall.desktop")
        }

        private fun String.xmlEscaped() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    /**
     * Windows Defender Firewall (edge cases N14 / N21). The per-user MSI can't add rules (ADR 0002 #13),
     * so the app offers to add one for its own executable, Private profile only, after a UAC prompt.
     */
    object WindowsFirewall {
        private const val RULE = "Zoocall"

        val isSupported: Boolean get() = isWindows && appPath != null

        /** null when it couldn't be checked. */
        fun hasRule(): Boolean? = runCatching {
            val process = ProcessBuilder("netsh", "advfirewall", "firewall", "show", "rule", "name=$RULE")
                .redirectErrorStream(true)
                .start()
            process.inputStream.readAllBytes()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy()
                return null
            }
            process.exitValue() == 0
        }.getOrNull()

        /** Shows the UAC prompt and adds the rule. Returns whether the rule exists afterwards. */
        fun addRule(): Boolean {
            val exe = appPath ?: return false
            // The script is passed base64-encoded, so no path characters can break quoting.
            val literal = "'" + exe.replace("'", "''") + "'"
            val script = """
                ${'$'}exe = $literal
                Start-Process -FilePath netsh.exe -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList @(
                  'advfirewall','firewall','add','rule','name=$RULE','dir=in','action=allow',
                  ('program="' + ${'$'}exe + '"'),'profile=private','enable=yes'
                )
            """.trimIndent()
            val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
            runCatching {
                val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.readAllBytes()
                process.waitFor(2, TimeUnit.MINUTES)
            }
            return hasRule() == true
        }
    }
}
