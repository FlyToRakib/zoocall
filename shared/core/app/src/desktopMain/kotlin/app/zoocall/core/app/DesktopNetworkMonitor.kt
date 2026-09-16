package app.zoocall.core.app

import app.zoocall.core.discovery.JmdnsDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/** The JVM has no network change callbacks, so interfaces are polled every few seconds. */
class DesktopNetworkMonitor(pollMs: Long = 5_000) : NetworkMonitor {
    private val _state = MutableStateFlow(compute())
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                delay(pollMs)
                _state.value = compute()
            }
        }
    }

    private fun compute(): NetworkState {
        val nics = runCatching { JmdnsDiscovery.lanInterfaces() }.getOrDefault(emptyList())
        val addresses = nics.flatMap { nic -> nic.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress } }
            .mapNotNull { it.hostAddress }
            .distinct()
        val vpn = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { nic ->
                nic.isUp && VPN_HINTS.any { nic.name.lowercase().contains(it) || nic.displayName.orEmpty().lowercase().contains(it) }
            }
        }.getOrDefault(false)
        return NetworkState(
            onLocalNetwork = addresses.isNotEmpty(),
            localAddresses = addresses,
            vpnActive = vpn,
            adapter = nics.firstOrNull()?.let { NetworkAdapter(it.displayName ?: it.name, kindOf(it)) },
            ignoredAdapters = runCatching { JmdnsDiscovery.ignoredAdapters() }.getOrDefault(emptyList()),
        )
    }

    private fun kindOf(nic: NetworkInterface): AdapterKind {
        val text = "${nic.name} ${nic.displayName.orEmpty()}".lowercase()
        return when {
            WIFI_HINTS.any { it in text } -> AdapterKind.WiFi
            ETHERNET_HINTS.any { it in text } -> AdapterKind.Ethernet
            else -> AdapterKind.Other
        }
    }

    private companion object {
        val VPN_HINTS = listOf("vpn", "tun", "utun", "wireguard", "tailscale", "zerotier")
        val WIFI_HINTS = listOf("wlan", "wi-fi", "wifi", "wireless", "802.11")
        val ETHERNET_HINTS = listOf("eth", "ethernet", "gbe", "lan")
    }
}
