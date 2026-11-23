package com.ronova.config.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ronova.config.R
import com.ronova.config.adb.AdbConnectionState
import com.ronova.config.adb.AdbProtocol
import com.ronova.config.data.ConfigRepository
import com.ronova.config.data.ConfigState
import com.ronova.config.data.EngineRuntime
import com.ronova.config.data.EngineRuntimeState
import com.ronova.config.data.ModuleId
import com.ronova.config.data.isModuleEnabled
import com.ronova.config.data.withModuleEnabled
import com.ronova.config.engine.ApplyReport
import com.ronova.config.engine.EngineHooks
import com.ronova.config.engine.HotReloadDiffController
import com.ronova.config.engine.OptimizationEngine
import com.ronova.config.engine.StepOutcome
import com.ronova.config.overlay.ZeroLagFpsOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * Foreground + VpnService host for the whole engine.
 * - START_STICKY: restarts after a kill and re-attaches VPN/overlay/locks without re-applying settings.
 * - Owns the ongoing notification (status, stop button, expandable quick-toggle panel, Re-enable).
 * - Implements EngineHooks so the engine can start/stop the packet sinkhole and the FPS overlay.
 */
class LatencyGuardService : VpnService(), EngineHooks {

    companion object {
        const val CHANNEL_ID = "ronova_engine"
        const val NOTIFICATION_ID = 4401

        const val ACTION_ENABLE = "com.ronova.config.action.ENABLE"
        const val ACTION_DISABLE = "com.ronova.config.action.DISABLE"
        const val ACTION_TOGGLE_MODULE = "com.ronova.config.action.TOGGLE_MODULE"
        const val ACTION_REAPPLY = "com.ronova.config.action.REAPPLY"
        const val ACTION_REFRESH = "com.ronova.config.action.REFRESH"
        const val EXTRA_MODULE = "module"

        fun enable(context: Context) = startAction(context, ACTION_ENABLE)
        fun disable(context: Context) = startAction(context, ACTION_DISABLE)
        fun reapply(context: Context) = startAction(context, ACTION_REAPPLY)

        private fun startAction(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LatencyGuardService::class.java).setAction(action)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opMutex = Mutex()
    private val vpnLock = Any()

    private lateinit var repo: ConfigRepository
    private lateinit var adb: AdbProtocol
    private lateinit var engine: OptimizationEngine
    private lateinit var diff: HotReloadDiffController
    private lateinit var fpsOverlay: ZeroLagFpsOverlay
    private lateinit var nm: NotificationManager

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var maintenanceJob: Job? = null

    @Volatile
    private var busyLabel: String? = null

    @Volatile
    private var cachedPending: ConfigState = ConfigState()

    @Volatile
    private var cachedActive: ConfigState? = null

    // ───────────────────────── Lifecycle ─────────────────────────

    override fun onCreate() {
        super.onCreate()
        repo = ConfigRepository.get(this)
        adb = AdbProtocol.get(this)
        engine = OptimizationEngine.get(this)
        engine.hooks = this
        diff = HotReloadDiffController(engine)
        fpsOverlay = ZeroLagFpsOverlay(this, adb, scope)
        nm = getSystemService(NotificationManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification())
        when (intent?.action) {
            ACTION_ENABLE -> scope.launch { enableEngine() }
            ACTION_DISABLE -> scope.launch { disableEngine() }
            ACTION_TOGGLE_MODULE -> {
                val name = intent.getStringExtra(EXTRA_MODULE)
                scope.launch { togglePending(name) }
            }
            ACTION_REAPPLY -> scope.launch { reapply() }
            ACTION_REFRESH -> scope.launch { refreshNotification() }
            null -> scope.launch { restoreAfterKill() }
            else -> scope.launch { refreshNotification() }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        closeVpn()
        EngineRuntime.setMessage("VPN permission was revoked; packet sinkhole stopped")
        postNotification()
        super.onRevoke()
    }

    override fun onDestroy() {
        maintenanceJob?.cancel()
        closeVpn()
        fpsOverlay.detachNow()
        if (engine.hooks === this) engine.hooks = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (_: Exception) {
        }
    }

    // ───────────────────────── Engine control ─────────────────────────

    private fun summarize(report: ApplyReport): String {
        val n = report.results.size
        return if (report.allSucceeded) {
            "All $n steps applied in ${report.totalDurationMs} ms"
        } else {
            "${report.failed.size} of $n steps failed: " + report.failed.joinToString { it.optionKey }
        }
    }

    private fun setStarting(percent: Int, label: String, etaMs: Long) {
        EngineRuntime.set(EngineRuntimeState.Starting(percent, label, etaMs))
        postNotification()
    }

    private fun failAndStop(message: String) {
        EngineRuntime.setMessage(message)
        EngineRuntime.set(EngineRuntimeState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun enableEngine() {
        opMutex.withLock {
            if (EngineRuntime.state.value is EngineRuntimeState.Active) {
                refreshNotification()
                return
            }
            val config = repo.load().copy(isEnabled = true)
            repo.save(config)
            cachedPending = config
            EngineRuntime.setMessage(null)
            setStarting(0, "Connecting to wireless ADB", 0L)

            if (adb.connectionState.value !is AdbConnectionState.Connected) {
                val c = adb.connectAuto()
                if (c.isFailure) {
                    failAndStop("ADB connect failed: ${c.exceptionOrNull()?.message}")
                    return
                }
            }
            val probe = adb.executeShell("echo ok")
            if (!probe.success) {
                failAndStop("ADB probe failed: ${probe.errorMessage ?: probe.stderr}")
                return
            }

            val report = engine.applyAll(config) { pct, label, eta -> setStarting(pct, label, eta) }
            repo.saveActiveConfig(config)
            cachedActive = config
            EngineRuntime.set(EngineRuntimeState.Active)
            EngineRuntime.setMessage(summarize(report))
            startMaintenance()
            refreshNotification()
        }
    }

    private suspend fun disableEngine() {
        opMutex.withLock {
            val previous = repo.loadActiveConfig() ?: repo.load()
            EngineRuntime.set(EngineRuntimeState.Stopping)
            postNotification()

            val probe = adb.executeShell("echo ok")
            if (!probe.success) {
                // Settings such as wm size are still modified; keep ACTIVE so DISABLE can be retried.
                EngineRuntime.set(EngineRuntimeState.Active)
                EngineRuntime.setMessage("Cannot reach wireless ADB. Turn Wireless debugging back on and press DISABLE again.")
                refreshNotification()
                return
            }

            maintenanceJob?.cancel()
            val report = engine.revertAll(previous)
            closeVpn()
            fpsOverlay.stop()
            repo.saveActiveConfig(null)
            repo.update { it.copy(isEnabled = false) }
            cachedActive = null
            EngineRuntime.set(EngineRuntimeState.Idle)
            EngineRuntime.setMessage(summarize(report))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun togglePending(moduleName: String?) {
        val id = moduleName?.let { name -> ModuleId.entries.firstOrNull { it.name == name } } ?: return
        val updated = repo.update { it.withModuleEnabled(id, !it.isModuleEnabled(id)) }
        cachedPending = updated
        refreshNotification()
    }

    private suspend fun reapply() {
        opMutex.withLock {
            val active = repo.loadActiveConfig() ?: return
            val pending = repo.load().copy(isEnabled = true)
            val delta = diff.computeDelta(active, pending)
            if (delta.isEmpty) {
                busyLabel = null
                refreshNotification()
                return
            }
            busyLabel = "Applying ${delta.steps.size} change(s)..."
            postNotification()
            val report = diff.hotReloadDiff(active, pending)
            repo.saveActiveConfig(pending)
            cachedActive = pending
            cachedPending = pending
            EngineRuntime.setMessage(summarize(report))
            busyLabel = null
            refreshNotification()
        }
    }

    private suspend fun restoreAfterKill() {
        opMutex.withLock {
            val active = repo.loadActiveConfig()
            if (active == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            cachedActive = active
            cachedPending = repo.load()
            EngineRuntime.set(EngineRuntimeState.Active)
            if (active.networkShield.enabled && active.networkShield.vpnPacketSinkhole) setVpn(active, true)
            if (active.fpsOverlay.enabled) setFpsOverlay(active, true)
            engine.reacquireLocks(active)
            startMaintenance()
            refreshNotification()
        }
    }

    private fun startMaintenance() {
        maintenanceJob?.cancel()
        maintenanceJob = scope.launch {
            var lastWarnings: List<String> = emptyList()
            while (isActive) {
                delay(15_000L)
                val cfg = cachedActive ?: continue
                engine.refreshLmkdLock(cfg)
                engine.refreshWarnings(cfg)
                val now = engine.warnings.value
                if (now != lastWarnings) {
                    lastWarnings = now
                    refreshNotification()
                }
            }
        }
    }

    // ───────────────────────── EngineHooks ─────────────────────────

    override suspend fun setVpn(config: ConfigState, enabled: Boolean): StepOutcome =
        withContext(Dispatchers.IO) {
            if (!enabled) {
                closeVpn()
                return@withContext StepOutcome(true, "vpn:stop", "Packet sinkhole stopped")
            }
            if (prepare(this@LatencyGuardService) != null) {
                return@withContext StepOutcome(
                    false, "vpn:prepare",
                    "VPN permission not granted. Open the app and approve the VPN dialog once."
                )
            }
            closeVpn()
            try {
                val builder = Builder()
                    .setSession("Ronova Sinkhole")
                    .setMtu(1500)
                    .setBlocking(true)
                    .addAddress("10.77.0.2", 32)
                    .addAddress("fd77:7700::2", 128)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                // Apps listed here bypass the VPN (normal network): this app (ADB stays reachable) and the game.
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
                try { builder.addDisallowedApplication(config.targetPackage) } catch (_: Exception) {}
                val dns = config.networkShield.customDns.trim()
                if (dns.isNotEmpty() && dns.all { it.isDigit() || it == '.' }) {
                    try { builder.addDnsServer(InetAddress.getByName(dns)) } catch (_: Exception) {}
                }
                val pfd = builder.establish()
                    ?: return@withContext StepOutcome(false, "vpn:establish", "VPN interface could not be established")
                synchronized(vpnLock) {
                    vpnInterface = pfd
                    vpnThread = thread(isDaemon = true, name = "ronova-sinkhole") {
                        val stream = FileInputStream(pfd.fileDescriptor)
                        val buffer = ByteArray(32767)
                        try {
                            while (!Thread.currentThread().isInterrupted) {
                                if (stream.read(buffer) < 0) break
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                StepOutcome(true, "vpn:start", "Sinkhole active; ${config.targetPackage} and this app bypass it")
            } catch (e: Exception) {
                StepOutcome(false, "vpn:start", e.message ?: e.javaClass.simpleName)
            }
        }

    override suspend fun setFpsOverlay(config: ConfigState, enabled: Boolean): StepOutcome {
        if (!enabled) {
            fpsOverlay.stop()
            return StepOutcome(true, "fps:stop", "FPS counter stopped")
        }
        if (!Settings.canDrawOverlays(this)) {
            return StepOutcome(false, "fps:start", "Display over other apps permission is not granted")
        }
        val ok = fpsOverlay.start(config.targetPackage, config.fpsOverlay.refreshIntervalMs)
        return StepOutcome(ok, "fps:start", if (ok) "FPS counter running" else "Could not start the overlay")
    }

    private fun closeVpn() {
        synchronized(vpnLock) {
            vpnThread?.interrupt()
            vpnThread = null
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
        }
    }

    // ───────────────────────── Notification ─────────────────────────

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Engine status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows whether Ronova's Config is active and lets you toggle modules mid-game"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun postNotification() {
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private suspend fun refreshNotification() {
        cachedPending = repo.load()
        cachedActive = repo.loadActiveConfig()
        postNotification()
    }

    private fun servicePendingIntent(action: String, requestCode: Int, module: ModuleId? = null): PendingIntent {
        val intent = Intent(this, LatencyGuardService::class.java).setAction(action)
        if (module != null) intent.putExtra(EXTRA_MODULE, module.name)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildPanel(pending: ConfigState, active: ConfigState?, accent: Int): RemoteViews {
        val rv = RemoteViews(packageName, R.layout.notification_panel)
        val changes = if (active != null) diff.computeDelta(active, pending).steps.size else 0
        rv.setTextViewText(
            R.id.panel_title,
            if (changes > 0) "ACTIVE - $changes pending change(s), tap Re-enable" else "ACTIVE - tap a module to toggle"
        )
        val views = listOf(
            ModuleId.RENDER_SCALER to R.id.t_scaler,
            ModuleId.CUTOUT_CRASH_GUARD to R.id.t_cutout,
            ModuleId.FRAME_PACING to R.id.t_pacing,
            ModuleId.NETWORK_SHIELD to R.id.t_network,
            ModuleId.BLUETOOTH_ISOLATOR to R.id.t_bluetooth,
            ModuleId.FPS_OVERLAY to R.id.t_fps
        )
        views.forEachIndexed { index, (id, viewId) ->
            val on = pending.isModuleEnabled(id)
            val changed = active != null && on != active.isModuleEnabled(id)
            val label = (if (on) "● " else "○ ") + id.shortName + (if (changed) " *" else "")
            rv.setTextViewText(viewId, label)
            rv.setTextColor(viewId, if (on) accent else 0xFF9E9E9E.toInt())
            rv.setOnClickPendingIntent(viewId, servicePendingIntent(ACTION_TOGGLE_MODULE, 100 + index, id))
        }
        return rv
    }

    private fun buildNotification(): Notification {
        val state = EngineRuntime.state.value
        val pending = cachedPending
        val active = cachedActive
        val accent = pending.theme.primaryColorInt()

        val title: String
        val text: String
        when (state) {
            is EngineRuntimeState.Starting -> {
                title = "Ronova's Config - Starting ${state.percent}%"
                text = "${state.stepLabel} - ${(state.etaMs + 999L) / 1000L}s left"
            }
            is EngineRuntimeState.Active -> {
                title = "Ronova's Config - ACTIVE"
                val warning = engine.warnings.value.firstOrNull()
                val modules = ModuleId.entries
                    .filter { (active ?: pending).isModuleEnabled(it) }
                    .joinToString(" - ") { it.shortName }
                text = busyLabel ?: warning ?: modules
            }
            is EngineRuntimeState.Stopping -> {
                title = "Ronova's Config - Stopping"
                text = "Restoring display and system settings..."
            }
            is EngineRuntimeState.Idle -> {
                title = "Ronova's Config"
                text = "Starting service..."
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ronova)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(accent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            // Android 14+ lets users swipe away foreground-service notifications; re-post if that happens.
            .setDeleteIntent(servicePendingIntent(ACTION_REFRESH, 1))
            .addAction(0, "✕ Stop", servicePendingIntent(ACTION_DISABLE, 2))

        if (state is EngineRuntimeState.Starting) {
            builder.setProgress(100, state.percent, false)
        }
        if (state is EngineRuntimeState.Active && pending.theme.notificationPanel) {
            builder.addAction(0, "Re-enable", servicePendingIntent(ACTION_REAPPLY, 3))
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            builder.setCustomBigContentView(buildPanel(pending, active, accent))
        }
        return builder.build()
    }
}
