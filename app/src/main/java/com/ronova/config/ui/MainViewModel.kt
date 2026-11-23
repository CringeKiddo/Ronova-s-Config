package com.ronova.config.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ronova.config.adb.AdbConnectionState
import com.ronova.config.adb.AdbDiscoveryState
import com.ronova.config.adb.AdbProtocol
import com.ronova.config.data.ConfigRepository
import com.ronova.config.data.ConfigState
import com.ronova.config.data.EngineRuntime
import com.ronova.config.data.EngineRuntimeState
import com.ronova.config.engine.HotReloadDiffController
import com.ronova.config.engine.OptimizationEngine
import com.ronova.config.receiver.PairingNotifier
import com.ronova.config.service.LatencyGuardService
import com.ronova.config.ui.components.AppEntry
import com.ronova.config.ui.components.DiagnosticsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PairingUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val success: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val repo = ConfigRepository.get(app)
    private val adb = AdbProtocol.get(app)
    private val diff = HotReloadDiffController(OptimizationEngine.get(app))
    private val monitor = DiagnosticsMonitor(app, adb)

    val config: StateFlow<ConfigState> =
        repo.configFlow.stateIn(viewModelScope, SharingStarted.Eagerly, ConfigState())

    private val activeConfig: StateFlow<ConfigState?> =
        repo.activeConfigFlow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val pendingChanges: StateFlow<Int> =
        combine(config, activeConfig) { pending, active ->
            if (active == null) 0 else diff.computeDelta(active, pending.copy(isEnabled = true)).steps.size
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val runtime: StateFlow<EngineRuntimeState> = EngineRuntime.state
    val message: StateFlow<String?> = EngineRuntime.message
    val discovery: StateFlow<AdbDiscoveryState> = adb.discoveryState
    val connection: StateFlow<AdbConnectionState> = adb.connectionState
    val diagnostics: StateFlow<DiagnosticsState> = monitor.state

    private val pairedTick = MutableStateFlow(0)

    val adbReady: StateFlow<Boolean> =
        combine(adb.connectionState, pairedTick) { conn, _ ->
            conn is AdbConnectionState.Connected || adb.isPaired
        }.stateIn(viewModelScope, SharingStarted.Eagerly, adb.isPaired)

    private val _pairing = MutableStateFlow(PairingUiState())
    val pairing: StateFlow<PairingUiState> = _pairing.asStateFlow()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    init {
        viewModelScope.launch { EngineRuntime.restore(app) }
        viewModelScope.launch { _apps.value = loadInstalledApps() }
    }

    // ───────────────────────── Config ─────────────────────────

    fun updateConfig(transform: (ConfigState) -> ConfigState) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun setConfig(newConfig: ConfigState) = updateConfig { newConfig }

    // ───────────────────────── ADB ─────────────────────────

    fun startDiscovery() = adb.startDiscovery()

    fun refreshAdbState() {
        pairedTick.value = pairedTick.value + 1
    }

    fun showPairingNotification() = PairingNotifier.show(app)

    fun pair(code: String) {
        if (_pairing.value.busy) return
        viewModelScope.launch {
            _pairing.value = PairingUiState(busy = true, message = "Pairing...")
            adb.startDiscovery()
            val pairResult = adb.pairWithDiscovered(code)
            if (pairResult.isFailure) {
                _pairing.value = PairingUiState(
                    busy = false,
                    message = pairResult.exceptionOrNull()?.message ?: "Pairing failed",
                    success = false
                )
                return@launch
            }
            refreshAdbState()
            _pairing.value = PairingUiState(busy = true, message = "Paired. Connecting...", success = true)
            val connectResult = adb.connectAuto()
            refreshAdbState()
            _pairing.value = if (connectResult.isSuccess) {
                PairingUiState(false, "Paired and connected", true)
            } else {
                PairingUiState(
                    false,
                    "Paired, but connecting failed: ${connectResult.exceptionOrNull()?.message}. Toggle Wireless debugging off and on.",
                    true
                )
            }
            PairingNotifier.cancel(app)
        }
    }

    // ───────────────────────── Engine / notification ─────────────────────────

    /** Re-posts the service notification so the panel/theme reflect the latest saved settings. */
    fun refreshNotificationIfActive() {
        if (runtime.value is EngineRuntimeState.Active) {
            ContextCompat.startForegroundService(
                app,
                Intent(app, LatencyGuardService::class.java).setAction(LatencyGuardService.ACTION_REFRESH)
            )
        }
    }

    fun setDiagnosticsActive(active: Boolean) {
        if (active) monitor.start(viewModelScope) else monitor.stop()
    }

    // ───────────────────────── Installed apps ─────────────────────────

    @Suppress("DEPRECATION")
    private suspend fun loadInstalledApps(): List<AppEntry> = withContext(Dispatchers.Default) {
        val pm = app.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName
            if (!seen.add(pkg)) continue
            val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            val icon = try { ri.loadIcon(pm).toBitmap(72, 72).asImageBitmap() } catch (_: Exception) { null }
            val isGame = ai.category == ApplicationInfo.CATEGORY_GAME ||
                (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            result += AppEntry(label, pkg, icon, isGame)
        }
        result.sortedWith(
            compareByDescending<AppEntry> { it.packageName == "com.nvidia.geforcenow" }
                .thenByDescending { it.isGame }
                .thenBy { it.label.lowercase() }
        )
    }

    override fun onCleared() {
        monitor.stop()
        super.onCleared()
    }
}
