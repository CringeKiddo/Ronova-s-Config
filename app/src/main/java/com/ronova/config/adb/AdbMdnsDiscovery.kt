package com.ronova.config.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

data class AdbEndpoint(
    val host: String,
    val port: Int,
    val serviceName: String
)

data class AdbDiscoveryState(
    val isScanning: Boolean = false,
    val pairingEndpoint: AdbEndpoint? = null,
    val connectEndpoint: AdbEndpoint? = null,
    val error: String? = null
)

/**
 * Finds this device's own wireless-debugging services via mDNS.
 * Only endpoints whose address belongs to this phone (or loopback) are accepted, so other
 * phones on the LAN with Wireless debugging enabled can never be picked by mistake.
 */
class AdbMdnsDiscovery(context: Context) {

    companion object {
        const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
        const val TYPE_CONNECT = "_adb-tls-connect._tcp"
    }

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val lock = Any()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null
    private var connectListener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<Pair<NsdServiceInfo, Boolean>>()
    private var resolving = false

    private val _state = MutableStateFlow(AdbDiscoveryState())
    val state: StateFlow<AdbDiscoveryState> = _state.asStateFlow()

    fun start() {
        var pl: NsdManager.DiscoveryListener? = null
        var cl: NsdManager.DiscoveryListener? = null
        synchronized(lock) {
            if (pairingListener == null) {
                multicastLock = wifi.createMulticastLock("ronova-adb-mdns").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                pl = newListener(true)
                cl = newListener(false)
                pairingListener = pl
                connectListener = cl
            }
        }
        val pairing = pl ?: return
        val connect = cl ?: return
        _state.value = AdbDiscoveryState(isScanning = true)
        try {
            nsd.discoverServices(TYPE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairing)
            nsd.discoverServices(TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connect)
        } catch (e: Exception) {
            _state.update { it.copy(isScanning = false, error = e.message ?: "mDNS discovery failed") }
        }
    }

    fun stop() {
        var pl: NsdManager.DiscoveryListener? = null
        var cl: NsdManager.DiscoveryListener? = null
        synchronized(lock) {
            pl = pairingListener
            cl = connectListener
            pairingListener = null
            connectListener = null
            queue.clear()
            resolving = false
        }
        pl?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        cl?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        synchronized(lock) {
            try { multicastLock?.release() } catch (_: Exception) {}
            multicastLock = null
        }
        _state.value = AdbDiscoveryState(isScanning = false)
    }

    private fun newListener(isPairing: Boolean) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            _state.update { it.copy(error = "mDNS start failed ($errorCode) for $serviceType") }
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        override fun onDiscoveryStarted(serviceType: String?) {}
        override fun onDiscoveryStopped(serviceType: String?) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            synchronized(lock) { queue.addLast(serviceInfo to isPairing) }
            processQueue()
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            _state.update { s ->
                when {
                    isPairing && s.pairingEndpoint?.serviceName == serviceInfo.serviceName ->
                        s.copy(pairingEndpoint = null)
                    !isPairing && s.connectEndpoint?.serviceName == serviceInfo.serviceName ->
                        s.copy(connectEndpoint = null)
                    else -> s
                }
            }
        }
    }

    private fun processQueue() {
        var next: Pair<NsdServiceInfo, Boolean>? = null
        synchronized(lock) {
            if (!resolving && queue.isNotEmpty()) {
                next = queue.removeFirst()
                resolving = true
            }
        }
        val item = next ?: return
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(item.first, resolveListener(item.second))
        } catch (_: Exception) {
            synchronized(lock) { resolving = false }
            processQueue()
        }
    }

    private fun resolveListener(isPairing: Boolean) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
            synchronized(lock) { resolving = false }
            processQueue()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            val addr: InetAddress? = serviceInfo.host
            if (addr != null && isLocalAddress(addr)) {
                val endpoint = AdbEndpoint(
                    host = addr.hostAddress ?: "",
                    port = serviceInfo.port,
                    serviceName = serviceInfo.serviceName ?: ""
                )
                _state.update {
                    if (isPairing) it.copy(pairingEndpoint = endpoint, error = null)
                    else it.copy(connectEndpoint = endpoint, error = null)
                }
            }
            synchronized(lock) { resolving = false }
            processQueue()
        }
    }

    private fun normalize(addr: InetAddress): String =
        (addr.hostAddress ?: "").substringBefore('%')

    private fun isLocalAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress) return true
        val target = normalize(addr)
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { ni ->
                Collections.list(ni.inetAddresses).any { normalize(it) == target }
            }
        } catch (_: Exception) {
            false
        }
    }
}
