package com.ronova.config.engine

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * Wraps PowerManager's thermal APIs (API 29+) to warn before the OS clamps CPU/GPU frequencies,
 * instead of only finding out once frames start dropping. Pure in-process platform API: no ADB
 * shell, no root, nothing that needs wireless debugging to be connected.
 *
 * getThermalHeadroom(forecastSeconds) returns a normalized 0.0+ value where 1.0 means the device
 * is (or is forecast to be) at THERMAL_STATUS_SEVERE. It costs nothing to poll on every status
 * change callback; the platform doc notes calling it faster than ~once/second just returns NaN.
 */
class ThermalMonitor(private val context: Context) {

    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    private var listener: PowerManager.OnThermalStatusChangedListener? = null
    private val immediateExecutor = Executor { it.run() }

    fun start() {
        if (listener != null) return
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        val l = PowerManager.OnThermalStatusChangedListener { status -> onStatus(pm, status) }
        listener = l
        try {
            pm.addThermalStatusListener(immediateExecutor, l)
        } catch (_: Exception) {
            listener = null
            return
        }
        onStatus(pm, pm.currentThermalStatus)
    }

    fun stop() {
        val l = listener ?: return
        val pm = context.getSystemService(PowerManager::class.java)
        try {
            pm?.removeThermalStatusListener(l)
        } catch (_: Exception) {
        }
        listener = null
        _warning.value = null
    }

    private fun onStatus(pm: PowerManager, status: Int) {
        val headroom = try {
            pm.getThermalHeadroom(10)
        } catch (_: Exception) {
            Float.NaN
        }
        _warning.value = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE ->
                "Thermal throttling active${headroomText(headroom)} - expect dropped frames until it cools"
            status == PowerManager.THERMAL_STATUS_MODERATE && !headroom.isNaN() && headroom > 0.8f ->
                "Approaching thermal throttling${headroomText(headroom)} - consider a lower render scale"
            else -> null
        }
    }

    private fun headroomText(headroom: Float): String =
        if (headroom.isNaN()) "" else ", headroom ${(headroom * 100).toInt()}% of severe"
}
