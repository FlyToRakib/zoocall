package app.zoocall.core.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.annotation.TargetApi
import app.zoocall.core.model.Logger
import app.zoocall.core.model.PeerAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Android discovery through `NsdManager`. Holds a multicast lock only while browsing. */
class NsdDiscovery(context: Context, private val logger: Logger = Logger.current) : Discovery {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null
    private var ownInstanceName: String? = null

    @Synchronized
    override fun advertise(record: ServiceRecord) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = record.instanceName
            serviceType = TxtRecord.SERVICE_TYPE
            port = record.port
            TxtRecord.from(record).toAttributes().forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Android may rename on conflict; remember the final name to filter ourselves out.
                ownInstanceName = info.serviceName
                logger.info(TAG, "Advertising on port ${record.port}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                logger.warn(TAG, "Advertise failed: $errorCode")
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        ownInstanceName = record.instanceName
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    override fun stopAdvertising() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    override fun browse(): Flow<DiscoveryEvent> = callbackFlow {
        val lock = wifi.createMulticastLock("zoocall-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val resolveExecutor = Executors.newSingleThreadExecutor()
        val infoCallbacks = ConcurrentHashMap<String, Any>()

        fun emitResolved(info: NsdServiceInfo) {
            val name = info.serviceName ?: return
            if (name == ownInstanceName) return
            val addresses = hostAddresses(info).map { PeerAddress(it.hostAddress!!.substringBefore('%'), info.port) }
            if (addresses.isEmpty() || info.port !in 1..65_535) return
            val attributes = info.attributes.mapValues { (_, v) -> v?.decodeToString() }
            trySendBlocking(DiscoveryEvent.Found(DiscoveredService(name, addresses, TxtRecord.parse(attributes))))
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = logger.info(TAG, "Browsing started")
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                logger.warn(TAG, "Browse failed: $errorCode")
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                if (name == ownInstanceName) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    if (infoCallbacks.containsKey(name)) return
                    val callback = infoCallback(::emitResolved) { trySendBlocking(DiscoveryEvent.Lost(name)) }
                    infoCallbacks[name] = callback
                    runCatching { nsd.registerServiceInfoCallback(info, resolveExecutor, callback) }
                        .onFailure { infoCallbacks.remove(name) }
                } else {
                    resolveExecutor.execute { resolveLegacy(info, ::emitResolved) }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    (infoCallbacks.remove(name) as? NsdManager.ServiceInfoCallback)?.let {
                        runCatching { nsd.unregisterServiceInfoCallback(it) }
                    }
                }
                trySendBlocking(DiscoveryEvent.Lost(name))
            }
        }

        nsd.discoverServices(TxtRecord.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                infoCallbacks.values.forEach { cb ->
                    runCatching { nsd.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) }
                }
            }
            resolveExecutor.shutdownNow()
            runCatching { lock.release() }
        }
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun infoCallback(onResolved: (NsdServiceInfo) -> Unit, onLost: () -> Unit) =
        object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
            override fun onServiceUpdated(info: NsdServiceInfo) = onResolved(info)
            override fun onServiceLost() = onLost()
            override fun onServiceInfoCallbackUnregistered() = Unit
        }

    /** Pre-34 `resolveService` allows one resolve at a time, so this runs on a single-thread executor. */
    @Suppress("DEPRECATION")
    private fun resolveLegacy(info: NsdServiceInfo, onResolved: (NsdServiceInfo) -> Unit) {
        val done = java.util.concurrent.CountDownLatch(1)
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done.countDown()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    onResolved(info)
                    done.countDown()
                }
            },
        )
        done.await(RESOLVE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun hostAddresses(info: NsdServiceInfo): List<InetAddress> {
        val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }
        // Prefer IPv4, then IPv6; skip loopback.
        return all.filterNot { it.isLoopbackAddress }
            .sortedBy { if (it is Inet4Address) 0 else if (it is Inet6Address) 1 else 2 }
    }

    private companion object {
        const val TAG = "Discovery"
        const val RESOLVE_TIMEOUT_S = 5L
    }
}
