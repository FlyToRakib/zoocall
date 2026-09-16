package app.zoocall.desktop

import app.zoocall.core.app.CheckStatus
import app.zoocall.core.app.DiagnosticResult
import app.zoocall.core.app.DoctorCheck
import app.zoocall.core.app.NetworkState
import app.zoocall.core.app.PlatformDiagnostics
import app.zoocall.core.discovery.JmdnsDiscovery
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Windows checks for Network Doctor: the network profile (N21) and the firewall rule (N14). */
class DesktopDiagnostics : PlatformDiagnostics {

    override suspend fun run(network: NetworkState): List<DiagnosticResult> = buildList {
        if (!DesktopIntegration.isWindows) return@buildList
        if (network.onLocalNetwork) {
            val profiles = networkProfiles()
            val public = profiles.firstOrNull { it.category.equals("Public", ignoreCase = true) }
            when {
                public != null -> add(DiagnosticResult(DoctorCheck.NetworkProfile, CheckStatus.Problem, listOf(public.name), fixable = true))
                profiles.isNotEmpty() -> add(DiagnosticResult(DoctorCheck.NetworkProfile, CheckStatus.Ok, listOf(profiles.first().name)))
            }
        }
        // Only installed builds have a stable executable to allow; `gradlew run` goes through java.exe.
        if (DesktopIntegration.WindowsFirewall.isSupported) {
            when (DesktopIntegration.WindowsFirewall.hasRule()) {
                true -> add(DiagnosticResult(DoctorCheck.Firewall, CheckStatus.Ok))
                false -> add(DiagnosticResult(DoctorCheck.Firewall, CheckStatus.Problem, fixable = true))
                null -> Unit
            }
        }
    }

    override suspend fun fix(check: DoctorCheck): Boolean = when (check) {
        DoctorCheck.Firewall -> DesktopIntegration.WindowsFirewall.addRule()
        DoctorCheck.NetworkProfile -> runCatching {
            // The empty title argument stops `start` treating the URI as a window title.
            ProcessBuilder("cmd.exe", "/c", "start", "", "ms-settings:network-status").start().waitFor(5, TimeUnit.SECONDS)
            true
        }.getOrDefault(false)
        else -> false
    }

    private data class Profile(val alias: String, val name: String, val category: String)

    /** Active connection profiles of real adapters (Hyper-V/WSL switches are usually "Public" and harmless). */
    private fun networkProfiles(): List<Profile> = runCatching {
        val script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; " +
            "Get-NetConnectionProfile | ForEach-Object { \$_.InterfaceAlias + '|' + \$_.Name + '|' + \$_.NetworkCategory }"
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroy()
            return emptyList()
        }
        output.lines()
            .mapNotNull { line -> line.trim().split('|').takeIf { it.size == 3 }?.let { Profile(it[0], it[1], it[2]) } }
            .filterNot { JmdnsDiscovery.isVirtualAdapterName(it.alias) }
    }.getOrDefault(emptyList())
}
