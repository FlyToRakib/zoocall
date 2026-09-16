package app.zoocall.desktop

import app.zoocall.core.app.PickedFile
import app.zoocall.core.model.AttachmentMeta
import app.zoocall.ui.platform.AudioRoute
import app.zoocall.ui.platform.FirewallHelper
import app.zoocall.ui.platform.SystemFeature
import app.zoocall.ui.platform.LoginItem
import app.zoocall.ui.platform.PlatformActions
import app.zoocall.ui.platform.RingtoneChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okio.Path
import okio.source
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files

/** Desktop OS integrations: native file dialogs, opening files, start at login and the firewall helper. */
class DesktopPlatformActions(private val dataDir: File) : PlatformActions {
    override val isDesktop = true
    override suspend fun ensureCallPermissions(video: Boolean) = true
    override val qrScanner: (suspend () -> String?)? = null
    override val audioRoutes: StateFlow<List<AudioRoute>> = MutableStateFlow(emptyList())
    override val currentAudioRoute: StateFlow<AudioRoute?> = MutableStateFlow(null)
    override fun selectAudioRoute(route: AudioRoute) = Unit
    override fun openAppSettings() = Unit

    override val canPickFiles = true

    /** Runs on the UI thread: the native dialog is modal and pumps events while open. */
    override suspend fun pickFile(): PickedFile? {
        val dialog = FileDialog(null as Frame?, "Zoocall", FileDialog.LOAD).apply { isVisible = true }
        val name = dialog.file ?: return null
        val file = File(dialog.directory, name)
        if (!file.isFile) return null
        val mime = withContext(Dispatchers.IO) { runCatching { Files.probeContentType(file.toPath()) }.getOrNull() } ?: "application/octet-stream"
        return PickedFile(file.name, mime, file.length()) { file.source() }
    }

    /** Opens a copy with the real file name, so the OS picks the right app. Only on the user's request. */
    override suspend fun openFile(path: Path, name: String, mime: String): Boolean {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(dataDir, "opened/${path.name}").apply { mkdirs() }
                val target = File(dir, AttachmentMeta.sanitizeName(name))
                if (!target.exists()) File(path.toString()).copyTo(target)
                Desktop.getDesktop().open(target)
                true
            }.getOrDefault(false)
        }
    }

    override suspend fun saveFile(path: Path, name: String, mime: String): Boolean {
        val dialog = FileDialog(null as Frame?, "Zoocall", FileDialog.SAVE).apply {
            file = AttachmentMeta.sanitizeName(name)
            isVisible = true
        }
        val chosen = dialog.file ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                File(path.toString()).copyTo(File(dialog.directory, chosen), overwrite = true)
                true
            }.getOrDefault(false)
        }
    }

    override val startAtLogin: LoginItem? =
        if (!DesktopIntegration.StartAtLogin.isSupported) null else object : LoginItem {
            override fun isEnabled() = DesktopIntegration.StartAtLogin.isEnabled()
            override suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) { DesktopIntegration.StartAtLogin.setEnabled(enabled) }
        }

    override val globalMuteShortcut: String? = if (DesktopIntegration.isWindows) GlobalMuteHotkey.LABEL else null

    /** Windows 11 Live Captions (on-device), or the macOS Live Captions settings page. */
    override val liveCaptions: SystemFeature? = run {
        val os = System.getProperty("os.name").lowercase()
        val windowsApp = File(System.getenv("WINDIR") ?: "C:\\Windows", "System32\\LiveCaptions.exe")
        when {
            os.contains("win") && windowsApp.exists() -> SystemFeature {
                runCatching { ProcessBuilder(windowsApp.absolutePath).start() }.isSuccess
            }
            os.contains("mac") && Desktop.isDesktopSupported() -> SystemFeature {
                runCatching { Desktop.getDesktop().browse(java.net.URI("x-apple.systempreferences:com.apple.preference.universalaccess?Captioning")) }.isSuccess
            }
            else -> null
        }
    }

    /** Windows Studio Effects (background blur, eye contact, voice focus) live in the camera settings. */
    override val cameraEffects: SystemFeature? = if (DesktopIntegration.isWindows) {
        SystemFeature { runCatching { ProcessBuilder("cmd.exe", "/c", "start", "", "ms-settings:camera").start() }.isSuccess }
    } else {
        null
    }

    /** Set once the ringer exists (see Main). */
    override var ringtones: RingtoneChooser? = null

    override val firewall: FirewallHelper? =
        if (!DesktopIntegration.WindowsFirewall.isSupported) null else object : FirewallHelper {
            override suspend fun hasRule() = withContext(Dispatchers.IO) { DesktopIntegration.WindowsFirewall.hasRule() }
            override suspend fun addRule() = withContext(Dispatchers.IO) { DesktopIntegration.WindowsFirewall.addRule() }
        }
}
