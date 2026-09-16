package app.zoocall.core.discovery

import app.zoocall.core.model.Logger
import app.zoocall.core.model.PeerAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Desktop discovery through JmDNS. One responder per real LAN IPv4 address; virtual adapters
 * (Hyper-V, WSL, Docker, VPN, VirtualBox, VMware) are ignored (edge case N20).
 */
class JmdnsDiscovery(private val logger: Logger = Logger.current) : Discovery {
    private val lock = Any()
    private var responders: List<JmDNS> = emptyList()
    private var ownInstanceName: String? = null

    private fun responders(): List<JmDNS> = synchronized(lock) {
        if (responders.isEmpty()) {
            responders = lanAddresses().mapNotNull { address ->
                runCatching { JmDNS.create(address, "zoocall-" + address.hostAddress.replace('.', '-')) }
                    .onFailure { logger.warn(TAG, "mDNS unavailable on an interface", it) }
                    .getOrNull()
            }
        }
        responders
    }

    override fun advertise(record: ServiceRecord) {
        stopAdvertising()
        ownInstanceName = record.instanceName
        val attributes = TxtRecord.from(record).toAttributes()
        responders().forEach { jmdns ->
            val info = ServiceInfo.create("${TxtRecord.SERVICE_TYPE}.local.", record.instanceName, record.port, 0, 0, attributes)
            runCatching { jmdns.registerService(info) }.onFailure { logger.warn(TAG, "Advertise failed", it) }
        }
        logger.info(TAG, "Advertising on port ${record.port}")
    }

    override fun stopAdvertising() {
        synchronized(lock) { responders }.forEach { runCatching { it.unregisterAllServices() } }
    }

    override fun browse(): Flow<DiscoveryEvent> = callbackFlow {
        val type = "${TxtRecord.SERVICE_TYPE}.local."
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                event.dns.requestServiceInfo(event.type, event.name, RESOLVE_TIMEOUT_MS)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                if (event.name != ownInstanceName) trySend(DiscoveryEvent.Lost(event.name))
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                if (event.name == ownInstanceName || info.port !in 1..65_535) return
                val addresses = (info.inet4Addresses.toList() + info.inet6Addresses.toList())
                    .filterNot { it.isLoopbackAddress }
                    .map { PeerAddress(it.hostAddress.substringBefore('%'), info.port) }
                    .distinct()
                if (addresses.isEmpty()) return
                val attributes = info.propertyNames.toList().associateWith { info.getPropertyString(it) }
                trySend(DiscoveryEvent.Found(DiscoveredService(event.name, addresses, TxtRecord.parse(attributes))))
            }
        }
        val active = responders()
        active.forEach { it.addServiceListener(type, listener) }
        awaitClose { active.forEach { runCatching { it.removeServiceListener(type, listener) } } }
    }

    fun close() = synchronized(lock) {
        responders.forEach { runCatching { it.close() } }
        responders = emptyList()
    }

    companion object {
        private const val TAG = "Discovery"
        private const val RESOLVE_TIMEOUT_MS = 3_000L
        private val VIRTUAL_ADAPTER_HINTS = listOf(
            "vethernet", "virtual", "docker", "wsl", "hyper-v", "vmware", "vbox", "virtualbox",
            "utun", "tun", "tap", "zerotier", "tailscale", "wireguard", "loopback", "bluetooth",
        )

        /** Real, up, non-virtual interfaces with a site-local IPv4 address, best first. */
        fun lanInterfaces(): List<NetworkInterface> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { nic -> isUsable(nic) && !isVirtualAdapter(nic) && nic.siteLocalV4().isNotEmpty() }

        fun lanAddresses(): List<InetAddress> = lanInterfaces().flatMap { it.siteLocalV4() }

        /** Display names of up virtual adapters that have a LAN-looking address but are skipped (edge case N20). */
        fun ignoredAdapters(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { nic -> isUsable(nic) && isVirtualAdapter(nic) && nic.siteLocalV4().isNotEmpty() }
            .map { it.displayName ?: it.name }
            .distinct()

        /** Matches adapter names such as Hyper-V "vEthernet (WSL)", Docker or VPN tunnels. */
        fun isVirtualAdapterName(name: String): Boolean = name.lowercase().let { n -> VIRTUAL_ADAPTER_HINTS.any { n.contains(it) } }

        private fun isUsable(nic: NetworkInterface) = runCatching { nic.isUp && !nic.isLoopback }.getOrDefault(false)

        private fun isVirtualAdapter(nic: NetworkInterface) =
            runCatching { nic.isVirtual || nic.isPointToPoint }.getOrDefault(false) ||
                isVirtualAdapterName(nic.name) || isVirtualAdapterName(nic.displayName.orEmpty())

        private fun NetworkInterface.siteLocalV4() = inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }
    }
}
