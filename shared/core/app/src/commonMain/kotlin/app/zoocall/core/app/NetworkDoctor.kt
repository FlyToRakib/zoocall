package app.zoocall.core.app

/** One line of the Network Doctor checklist (docs/05-ux-ui-design.md §5.7). */
enum class DoctorCheck { LocalNetwork, Listening, Vpn, Discovery, Reachability, Subnet, NetworkProfile, Firewall, Battery }

enum class CheckStatus { Ok, Warning, Problem }

/**
 * @param args values the UI puts into the localized text (counts, network names, addresses).
 * @param fixable the platform can fix it (or open the right settings page) via [PlatformDiagnostics.fix].
 */
data class DiagnosticResult(
    val check: DoctorCheck,
    val status: CheckStatus,
    val args: List<String> = emptyList(),
    val fixable: Boolean = false,
)

data class DoctorReport(
    val results: List<DiagnosticResult>,
    val network: NetworkState,
    val port: Int?,
) {
    val hasIssues: Boolean get() = results.any { it.status != CheckStatus.Ok }
}

/** Checks only the operating system can answer. Each shell provides one. */
interface PlatformDiagnostics {
    suspend fun run(network: NetworkState): List<DiagnosticResult>

    /** Fixes the issue or opens the settings page that does. Returns whether something happened. */
    suspend fun fix(check: DoctorCheck): Boolean = false

    object None : PlatformDiagnostics {
        override suspend fun run(network: NetworkState) = emptyList<DiagnosticResult>()
    }
}

/** Result of dialing contacts' last known addresses directly (unicast), bypassing discovery. */
enum class ProbeOutcome { NotTried, Reached, Failed }

/** Pure decision logic, kept apart from the I/O in [ZoocallCore.runNetworkDoctor] so it's unit-testable. */
internal object NetworkDoctor {
    const val DISCOVERY_WAIT_MS = 4_000L
    const val PROBE_TIMEOUT_MS = 4_000L
    const val MAX_PROBES = 3

    data class Inputs(
        val network: NetworkState,
        val port: Int?,
        /** Other Zoocall devices seen through mDNS. */
        val discovered: Int,
        /** Peers with a live secure connection. */
        val connected: Int,
        val probe: ProbeOutcome,
        /** Hosts contacts were last seen at, to spot a different subnet (N5). */
        val contactHosts: List<String>,
        val platform: List<DiagnosticResult>,
    )

    fun evaluate(inputs: Inputs): List<DiagnosticResult> = buildList {
        val net = inputs.network
        if (!net.onLocalNetwork) {
            add(DiagnosticResult(DoctorCheck.LocalNetwork, CheckStatus.Problem))
            if (net.vpnActive) add(DiagnosticResult(DoctorCheck.Vpn, CheckStatus.Warning))
            addAll(inputs.platform.filter { it.check == DoctorCheck.Battery })
            return@buildList
        }
        add(DiagnosticResult(DoctorCheck.LocalNetwork, CheckStatus.Ok))
        add(
            if (inputs.port != null) DiagnosticResult(DoctorCheck.Listening, CheckStatus.Ok, listOf(inputs.port.toString()))
            else DiagnosticResult(DoctorCheck.Listening, CheckStatus.Problem),
        )
        add(DiagnosticResult(DoctorCheck.Vpn, if (net.vpnActive) CheckStatus.Warning else CheckStatus.Ok))

        val directWorks = inputs.connected > 0 || inputs.probe == ProbeOutcome.Reached
        add(
            when {
                inputs.discovered > 0 -> DiagnosticResult(DoctorCheck.Discovery, CheckStatus.Ok, listOf(inputs.discovered.toString()))
                // Unicast works but mDNS is silent: multicast is filtered (N4).
                directWorks -> DiagnosticResult(DoctorCheck.Discovery, CheckStatus.Problem)
                else -> DiagnosticResult(DoctorCheck.Discovery, CheckStatus.Warning)
            },
        )
        when {
            directWorks -> add(DiagnosticResult(DoctorCheck.Reachability, CheckStatus.Ok, listOf(inputs.connected.coerceAtLeast(1).toString())))
            // Known contacts time out on this network: client isolation (N3) or a different segment (N5).
            inputs.probe == ProbeOutcome.Failed -> add(DiagnosticResult(DoctorCheck.Reachability, CheckStatus.Problem))
        }
        if (!directWorks && inputs.discovered == 0) {
            val local = net.localAddresses.mapNotNull(::subnet24)
            val remote = inputs.contactHosts.firstOrNull { host -> subnet24(host)?.let { it !in local } == true }
            if (local.isNotEmpty() && remote != null && inputs.contactHosts.none { subnet24(it) in local }) {
                add(DiagnosticResult(DoctorCheck.Subnet, CheckStatus.Warning, listOf(net.localAddresses.first(), remote)))
            }
        }
        addAll(inputs.platform)
    }

    /** "192.168.1" for "192.168.1.24"; null for anything that isn't dotted IPv4. */
    fun subnet24(host: String): String? {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { p -> p.toIntOrNull()?.let { it in 0..255 } != true }) return null
        return parts.take(3).joinToString(".")
    }
}
