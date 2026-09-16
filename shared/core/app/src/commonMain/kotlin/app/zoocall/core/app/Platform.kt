package app.zoocall.core.app

import app.zoocall.core.discovery.Discovery
import app.zoocall.core.model.DeviceClass
import app.zoocall.core.model.Logger
import app.zoocall.core.store.DatabaseDriverFactory
import app.zoocall.core.store.SecureKeyStore
import app.zoocall.media.MediaEngine
import kotlinx.coroutines.flow.StateFlow
import okio.FileSystem
import okio.Path

data class NetworkState(
    /** Connected to Wi-Fi or Ethernet (a network other devices can share). */
    val onLocalNetwork: Boolean,
    /** Usable local IP addresses, best first. */
    val localAddresses: List<String>,
    val vpnActive: Boolean = false,
    /** The adapter Zoocall uses, when known (edge case N20). */
    val adapter: NetworkAdapter? = null,
    /** Virtual adapters (Hyper-V, WSL, Docker, VPN…) that are up but deliberately not used. */
    val ignoredAdapters: List<String> = emptyList(),
)

enum class AdapterKind { WiFi, Ethernet, Hotspot, Other }

data class NetworkAdapter(val name: String, val kind: AdapterKind)

interface NetworkMonitor {
    val state: StateFlow<NetworkState>
}

/** Everything the shared core needs from the operating system. */
class Platform(
    val keyStore: SecureKeyStore,
    val databaseFactory: DatabaseDriverFactory,
    val discovery: Discovery,
    val mediaEngine: MediaEngine,
    val network: NetworkMonitor,
    val deviceClass: DeviceClass,
    val appVersion: String,
    val logger: Logger,
    /** File system for attachments (`FileSystem.SYSTEM` on Android and desktop). */
    val fileSystem: FileSystem,
    /** App-private directory where attachment files are kept. */
    val attachmentsDir: Path,
    /** OS-specific Network Doctor checks (Windows network profile, firewall, Android battery). */
    val diagnostics: PlatformDiagnostics = PlatformDiagnostics.None,
)
