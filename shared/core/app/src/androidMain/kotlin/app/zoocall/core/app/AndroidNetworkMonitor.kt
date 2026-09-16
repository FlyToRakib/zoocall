package app.zoocall.core.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Tracks whether the phone is on a shareable local network. Works when the phone itself hosts a
 * hotspot (edge case N2): the hotspot interface isn't the default network but has a LAN address.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(compute())
    override val state: StateFlow<NetworkState> = _state.asStateFlow()

    init {
        connectivity.registerNetworkCallback(
            android.net.NetworkRequest.Builder().build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refresh()
                override fun onLost(network: Network) = refresh()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
                override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = refresh()
            },
        )
    }

    private fun refresh() {
        _state.value = compute()
    }

    private fun compute(): NetworkState {
        val lan = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { nic -> nic.isUp && !nic.isLoopback && CELLULAR_OR_VPN.none { nic.name.startsWith(it) } }
                .sortedBy { nic -> if (nic.name.startsWith("wlan")) 0 else if (nic.name.startsWith("eth")) 1 else 2 }
                .map { nic -> nic to nic.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.mapNotNull { it.hostAddress } }
                .filter { (_, addresses) -> addresses.isNotEmpty() }
        }.getOrDefault(emptyList())
        val addresses = lan.flatMap { it.second }.distinct()
        val vpn = runCatching {
            connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        }.getOrDefault(false)
        return NetworkState(
            onLocalNetwork = addresses.isNotEmpty(),
            localAddresses = addresses,
            vpnActive = vpn,
            adapter = lan.firstOrNull()?.first?.name?.let { NetworkAdapter(it, kindOf(it)) },
        )
    }

    private fun kindOf(name: String): AdapterKind = when {
        HOTSPOT.any { name.startsWith(it) } -> AdapterKind.Hotspot
        name.startsWith("wlan") -> if (isWifiClient()) AdapterKind.WiFi else AdapterKind.Hotspot
        name.startsWith("eth") -> AdapterKind.Ethernet
        else -> AdapterKind.Other
    }

    private fun isWifiClient() = runCatching {
        connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(true)

    private companion object {
        val CELLULAR_OR_VPN = listOf("rmnet", "ccmni", "pdp", "tun", "ppp", "ipsec", "clat", "dummy")
        val HOTSPOT = listOf("ap", "swlan", "softap")
    }
}
