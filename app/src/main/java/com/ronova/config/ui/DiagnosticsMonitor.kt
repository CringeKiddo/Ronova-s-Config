package com.ronova.config.ui

import android.content.Context
import android.net.wifi.WifiManager
import com.ronova.config.adb.AdbConnectionState
import com.ronova.config.adb.AdbProtocol
import com.ronova.config.ui.components.DiagnosticsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/**
 * Wi-Fi band, link speed and router ping/jitter. Only runs while the Configure sheet is open,
 * so there is zero background cost during gameplay.
 */
class DiagnosticsMonitor(context: Context, private val adb: AdbProtocol) {

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val _state = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _state.value = try { sample() } catch (_: Exception) { DiagnosticsState() }
                delay(1_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = DiagnosticsState()
    }

    private suspend fun sample(): DiagnosticsState {
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo
        val freq = info?.frequency ?: 0
        val band = when {
            freq in 2400..2500 -> "2.4GHz"
            freq in 4900..5900 -> "5GHz"
            freq >= 5925 -> "6GHz"
            else -> "No Wi-Fi"
        }
        val link = (info?.linkSpeed ?: 0).coerceAtLeast(0)
        val gateway = gatewayAddress()
        var ping: Float? = null
        var jitter: Float? = null
        if (gateway != null && freq > 0) {
            val samples = pingSamples(gateway)
            if (samples.isNotEmpty()) {
                ping = samples.average().toFloat()
                if (samples.size >= 2) {
                    var sum = 0f
                    for (i in 1 until samples.size) sum += abs(samples[i] - samples[i - 1])
                    jitter = sum / (samples.size - 1)
                }
            }
        }
        return DiagnosticsState(band, link, jitter, freq, ping)
    }

    private fun gatewayAddress(): String? {
        @Suppress("DEPRECATION")
        val gw = wifi.dhcpInfo?.gateway ?: 0
        if (gw == 0) return null
        return "${gw and 0xff}.${(gw shr 8) and 0xff}.${(gw shr 16) and 0xff}.${(gw shr 24) and 0xff}"
    }

    private suspend fun pingSamples(host: String): List<Float> {
        if (adb.connectionState.value is AdbConnectionState.Connected) {
            val r = adb.executeShell("ping -c 4 -i 0.2 -W 1 $host")
            val times = Regex("time[=<]\\s*([0-9.]+)").findAll(r.stdout)
                .mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()
            if (times.isNotEmpty()) return times
        }
        return tcpSamples(host)
    }

    /** Fallback without ADB: time a TCP connect; a refused connection still measures the round trip. */
    private suspend fun tcpSamples(host: String): List<Float> {
        val out = ArrayList<Float>()
        repeat(4) {
            val t0 = System.nanoTime()
            var ok = true
            try {
                Socket().use { it.connect(InetSocketAddress(host, 80), 700) }
            } catch (_: ConnectException) {
                // connection refused: RTT sample is still valid
            } catch (_: Exception) {
                ok = false
            }
            if (ok) out += (System.nanoTime() - t0) / 1_000_000f
            delay(150L)
        }
        return out
    }
}
