package app.zoocall.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import app.zoocall.core.app.PickedFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.Path

data class AudioRoute(val id: String, val label: String, val icon: ImageVector)

/** "Start at login" on desktop. */
interface LoginItem {
    fun isEnabled(): Boolean

    /** Returns whether the change was applied. */
    suspend fun setEnabled(enabled: Boolean): Boolean
}

/** Windows Defender Firewall rule for the app (ADR 0002 #13). */
interface FirewallHelper {
    /** null when it couldn't be checked. */
    suspend fun hasRule(): Boolean?

    /** Shows the system elevation prompt; returns whether the rule exists afterwards. */
    suspend fun addRule(): Boolean
}

/** Ringtone choice: built-in tones picked in the app (desktop) or the system ringtone picker (Android). */
interface RingtoneChooser {
    /** Ids of tones built into the app; the first is the default. Empty when the system picker is used. */
    val builtIn: List<String>

    /** The system's name for a ringtone id ("" = the default ringtone); null when unknown or built in. */
    fun systemLabel(id: String): String? = null

    /** Shows the system picker. Returns the chosen id, or null when cancelled. */
    suspend fun pickFromSystem(current: String): String? = null

    fun preview(id: String) = Unit

    /** Stops a preview, never a real ringing call. */
    fun stopPreview() = Unit
}

/**
 * An on-device feature the operating system already provides, such as live captions (Windows Live
 * Captions, Android Live Caption) or camera and microphone AI effects (Windows Studio Effects).
 */
fun interface SystemFeature {
    /** Turns the feature on, or opens the settings page where it's turned on. Returns whether something opened. */
    fun open(): Boolean
}

/** The device's own lock (fingerprint, face, PIN or pattern), used for app lock. */
interface DeviceUnlock {
    /** Whether the device has a screen lock set up. */
    fun isAvailable(): Boolean

    /** Shows the system prompt. Returns true when the user proved it's them. */
    suspend fun unlock(title: String, subtitle: String): Boolean
}

/** OS integrations the shared UI can ask for. Each platform shell provides one. */
interface PlatformActions {
    val isDesktop: Boolean

    /** Asks for mic (and camera for video) right before a call. Returns whether the mic is granted. */
    suspend fun ensureCallPermissions(video: Boolean): Boolean

    /** Null when the device can't scan (desktop). */
    val qrScanner: (suspend () -> String?)?

    val audioRoutes: StateFlow<List<AudioRoute>>
    val currentAudioRoute: StateFlow<AudioRoute?>
    fun selectAudioRoute(route: AudioRoute)

    fun openAppSettings()

    /** Brings the call screen back when it lives in its own window/activity. */
    fun openCallScreen() = Unit

    val canPickFiles: Boolean get() = false

    /** Shows the system file picker. Null when cancelled. */
    suspend fun pickFile(): PickedFile? = null

    /** Opens a received file in another app, only when the user asks. Returns false when nothing can open it. */
    suspend fun openFile(path: Path, name: String, mime: String): Boolean = false

    /** Lets the user save a copy somewhere. Returns true when saved. */
    suspend fun saveFile(path: Path, name: String, mime: String): Boolean = false

    val startAtLogin: LoginItem? get() = null

    val firewall: FirewallHelper? get() = null

    val ringtones: RingtoneChooser? get() = null

    /** Label of the opt-in system-wide mute shortcut (e.g. "Ctrl+Shift+M"); null where it isn't available. */
    val globalMuteShortcut: String? get() = null

    /** System live captions for call audio (docs/01: on-device live captions). */
    val liveCaptions: SystemFeature? get() = null

    /** System camera and microphone effects: background blur, voice focus. */
    val cameraEffects: SystemFeature? get() = null

    /**
     * Android: asks the system for permission to capture the whole screen, right before each share.
     * Returns whether sharing may start. Desktop picks a screen or window inside the app instead.
     */
    suspend fun requestScreenCapture(): Boolean = false

    /** App lock through the device's lock; null where Zoocall asks for its own passcode instead (desktop). */
    val deviceUnlock: DeviceUnlock? get() = null
}

object NoPlatformActions : PlatformActions {
    override val isDesktop = true
    override suspend fun ensureCallPermissions(video: Boolean) = true
    override val qrScanner: (suspend () -> String?)? = null
    override val audioRoutes: StateFlow<List<AudioRoute>> = MutableStateFlow(emptyList())
    override val currentAudioRoute: StateFlow<AudioRoute?> = MutableStateFlow(null)
    override fun selectAudioRoute(route: AudioRoute) = Unit
    override fun openAppSettings() = Unit
}

val LocalPlatformActions = staticCompositionLocalOf<PlatformActions> { NoPlatformActions }

/** A boolean module of a QR code: `modules[y][x]`. */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}

expect fun encodeQr(text: String): QrMatrix?

/** Window width class used for adaptive layouts (phone < 600dp ≤ tablet < 840dp ≤ desktop). */
enum class WidthClass { Compact, Medium, Expanded }

@Composable
expect fun currentWidthClass(): WidthClass
