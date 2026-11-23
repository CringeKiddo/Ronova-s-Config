package com.ronova.config.engine

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import com.ronova.config.adb.AdbProtocol
import com.ronova.config.data.ConfigState
import com.ronova.config.data.ModuleId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.roundToInt

data class StepOutcome(val success: Boolean, val command: String, val output: String)

data class StepResult(
    val moduleId: ModuleId,
    val optionKey: String,
    val label: String,
    val command: String,
    val success: Boolean,
    val output: String
)

data class ApplyReport(
    val results: List<StepResult>,
    val totalDurationMs: Long
) {
    val failed: List<StepResult> get() = results.filter { !it.success }
    val allSucceeded: Boolean get() = results.all { it.success }
}

/** Implemented by LatencyGuardService: things that must live inside the service process. */
interface EngineHooks {
    suspend fun setVpn(config: ConfigState, enabled: Boolean): StepOutcome
    suspend fun setFpsOverlay(config: ConfigState, enabled: Boolean): StepOutcome
    suspend fun setThermalGuard(config: ConfigState, enabled: Boolean): StepOutcome
}

internal class StepDef(
    val module: ModuleId,
    val key: String,
    val label: String,
    val estimatedMs: Long,
    val pkgDependent: Boolean,
    val enabled: (ConfigState) -> Boolean,
    val fingerprint: (ConfigState) -> String,
    val apply: suspend (ConfigState) -> StepOutcome,
    val revert: suspend (ConfigState) -> StepOutcome
)

class OptimizationEngine private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: OptimizationEngine? = null

        fun get(context: Context): OptimizationEngine =
            instance ?: synchronized(this) {
                instance ?: OptimizationEngine(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val adb = AdbProtocol.get(appContext)
    private val opMutex = Mutex()

    @Volatile
    var hooks: EngineHooks? = null

    private val _lastReport = MutableStateFlow<ApplyReport?>(null)
    val lastReport: StateFlow<ApplyReport?> = _lastReport.asStateFlow()

    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()

    private var wifiLock: WifiManager.WifiLock? = null
    private var dnsSaved = false
    private var savedDnsMode = "opportunistic"
    private var savedDnsSpecifier = ""

    private var phantomKillerSaved = false
    private var savedMaxPhantomProcesses = "32"
    private var savedPhantomMonitorEnabled = "true"

    private var longPressSaved = false
    private var savedLongPressTimeout = "400"

    private var animationsSaved = false
    private var savedWindowAnimScale = "1.0"
    private var savedTransitionAnimScale = "1.0"
    private var savedAnimatorDurationScale = "1.0"

    private var refreshRateSaved = false
    private var savedMinRefreshRate = ""
    private var savedPeakRefreshRate = ""

    // ───────────────────────── Shell helpers ─────────────────────────

    private suspend fun sh(cmd: String): StepOutcome {
        val r = adb.executeShell(cmd)
        val text = buildString {
            append(r.stdout)
            if (r.stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(r.stderr)
            }
            r.errorMessage?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
        }.trim()
        return StepOutcome(r.success, cmd, text)
    }

    private suspend fun seq(vararg cmds: String): StepOutcome {
        val outs = ArrayList<StepOutcome>()
        for (c in cmds) outs += sh(c)
        return StepOutcome(
            outs.all { it.success },
            cmds.joinToString(" ; "),
            outs.joinToString("\n") { it.output }.trim()
        )
    }

    private fun noop(note: String) = StepOutcome(true, "(none)", note)

    // ───────────────────────── Module 1: render scaler ─────────────────────────

    private suspend fun nativeMetrics(): Triple<Int, Int, Int>? {
        val size = adb.executeShell("wm size").stdout
        val dens = adb.executeShell("wm density").stdout
        val m = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(size) ?: return null
        val d = Regex("Physical density:\\s*(\\d+)").find(dens) ?: return null
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), d.groupValues[1].toInt())
    }

    private fun even(v: Int) = v - (v % 2)

    private suspend fun applyScaler(c: ConfigState): StepOutcome {
        val s = c.renderScaler
        val log = StringBuilder()
        var ok = true

        if (s.scalePercent >= 100) {
            val r = seq("wm size reset", "wm density reset")
            ok = r.success
            log.append(r.output)
        } else {
            val n = nativeMetrics()
                ?: return StepOutcome(false, "wm size", "Could not read the physical display size")
            val f = s.scalePercent / 100.0
            val w = even((n.first * f).roundToInt())
            val h = even((n.second * f).roundToInt())
            val d = (n.third * f).roundToInt().coerceAtLeast(96)
            val size = sh("wm size ${w}x$h")
            if (!size.success) return StepOutcome(false, size.command, size.output)
            val dens = sh("wm density $d")
            if (!dens.success) {
                sh("wm size reset")
                return StepOutcome(false, dens.command, dens.output)
            }
            log.append("Display ${n.first}x${n.second}@${n.third} -> ${w}x$h@$d")
        }

        if (s.usePerAppGameDownscale) {
            if (Build.VERSION.SDK_INT >= 34) {
                val pkg = c.targetPackage
                val g = if (s.scalePercent >= 100) {
                    sh("cmd game reset $pkg")
                } else {
                    val ratio = String.format(Locale.US, "%.2f", s.scalePercent / 100.0)
                    sh("cmd game set --mode 2 --downscale $ratio $pkg")
                }
                log.append("\n").append(g.command).append(if (g.success) " ok" else " failed: ${g.output}")
            } else {
                log.append("\nPer-app game downscale needs Android 14+; skipped")
            }
        }
        return StepOutcome(ok, "wm size/density + cmd game", log.toString().trim())
    }

    private suspend fun revertScaler(c: ConfigState): StepOutcome {
        val r = seq("wm size reset", "wm density reset")
        val g = sh("cmd game reset ${c.targetPackage}")
        return StepOutcome(r.success, r.command + " ; " + g.command, (r.output + "\n" + g.output).trim())
    }

    // ───────────────────────── Module 2: cutout + crash guard ─────────────────────────

    private suspend fun applyCutoutReset(): StepOutcome {
        val diag = sh("dumpsys window displays | grep -i -E 'cutout|DisplayFrames' | head -n 6")
        val list = sh("cmd overlay list").output
        val cutoutPkgs = Regex("^\\[x\\]\\s+(\\S*cutout\\S*)", RegexOption.MULTILINE)
            .findAll(list).map { it.groupValues[1] }.toList()

        val disables = cutoutPkgs.map { sh("cmd overlay disable --user 0 $it") }
        val setting = sh("settings put secure display_cutout_emulation \"\"")
        val overscan = sh("wm overscan reset")

        val ok = disables.all { it.success } && setting.success
        val text = buildString {
            append("Diagnostics: ").append(diag.output.ifBlank { "n/a" })
            append("\nDisabled cutout overlays: ").append(if (cutoutPkgs.isEmpty()) "none active" else cutoutPkgs.joinToString())
            append("\nwm overscan: ").append(if (overscan.success) "reset" else "unavailable on this build")
        }
        return StepOutcome(ok, "overlay disable + settings put + wm overscan reset", text)
    }

    // am set-standby-bucket is a documented, non-root ADB command (unlike writing /proc/<pid>/oom_score_adj,
    // which needs root and was the old implementation here). It keeps the target app out of the restricted
    // standby buckets so its jobs/alarms/network aren't throttled if it's briefly backgrounded.
    private suspend fun applyActiveBucket(c: ConfigState): StepOutcome =
        sh("am set-standby-bucket ${c.targetPackage} active")

    private suspend fun revertActiveBucket(): StepOutcome = noop("Standby bucket left as-is; Android will re-bucket naturally")

    /** Re-asserted periodically: the OS can re-evaluate the bucket on its own schedule. */
    suspend fun refreshActiveBucket(config: ConfigState) {
        if (config.cutoutCrashGuard.enabled && config.cutoutCrashGuard.keepTargetAppActive) {
            sh("am set-standby-bucket ${config.targetPackage} active")
        }
    }

    // Android 12+'s phantom-process killer caps background child/renderer processes (32 by default,
    // shared across all apps) and kills the newest ones over that cap. GeForce NOW's WebView-backed
    // surfaces are exactly the kind of child process this targets, which is a plausible contributor to
    // the cutout/black-screen symptom. device_config + this settings key are writable from a plain
    // (non-root) ADB shell; confirmed against public non-root wireless-debugging guides.
    private suspend fun applyPhantomKillerDisable(): StepOutcome {
        if (!phantomKillerSaved) {
            val cur = sh("device_config get activity_manager max_phantom_processes").output.trim()
            savedMaxPhantomProcesses = cur.ifBlank { "32" }.let { if (it == "null") "32" else it }
            val mon = sh("settings get global settings_enable_monitor_phantom_procs").output.trim()
            savedPhantomMonitorEnabled = mon.ifBlank { "true" }.let { if (it == "null") "true" else it }
            phantomKillerSaved = true
        }
        return seq(
            "device_config put activity_manager max_phantom_processes 2147483647",
            "settings put global settings_enable_monitor_phantom_procs false"
        )
    }

    private suspend fun revertPhantomKillerDisable(): StepOutcome {
        if (!phantomKillerSaved) return noop("Phantom process killer was never changed")
        val r = seq(
            "device_config put activity_manager max_phantom_processes $savedMaxPhantomProcesses",
            "settings put global settings_enable_monitor_phantom_procs $savedPhantomMonitorEnabled"
        )
        phantomKillerSaved = false
        return r
    }

    // ───────────────────────── Module 7: touch & animation latency ─────────────────────────

    private suspend fun applyLongPressTimeout(): StepOutcome {
        if (!longPressSaved) {
            val cur = sh("settings get secure long_press_timeout").output.trim()
            savedLongPressTimeout = cur.ifBlank { "400" }.let { if (it == "null") "400" else it }
            longPressSaved = true
        }
        return sh("settings put secure long_press_timeout 250")
    }

    private suspend fun revertLongPressTimeout(): StepOutcome {
        if (!longPressSaved) return noop("Long-press timeout was never changed")
        val r = sh("settings put secure long_press_timeout $savedLongPressTimeout")
        longPressSaved = false
        return r
    }

    private suspend fun applyAnimationScales(): StepOutcome {
        if (!animationsSaved) {
            savedWindowAnimScale = sh("settings get global window_animation_scale").output.trim().ifBlank { "1.0" }
            savedTransitionAnimScale = sh("settings get global transition_animation_scale").output.trim().ifBlank { "1.0" }
            savedAnimatorDurationScale = sh("settings get global animator_duration_scale").output.trim().ifBlank { "1.0" }
            animationsSaved = true
        }
        return seq(
            "settings put global window_animation_scale 0",
            "settings put global transition_animation_scale 0",
            "settings put global animator_duration_scale 0"
        )
    }

    private suspend fun revertAnimationScales(): StepOutcome {
        if (!animationsSaved) return noop("Animation scales were never changed")
        val r = seq(
            "settings put global window_animation_scale $savedWindowAnimScale",
            "settings put global transition_animation_scale $savedTransitionAnimScale",
            "settings put global animator_duration_scale $savedAnimatorDurationScale"
        )
        animationsSaved = false
        return r
    }

    // ───────────────────────── Module 8: refresh-rate floor ─────────────────────────

    // Settings.System.min_refresh_rate / peak_refresh_rate (Android 11+) are the actual keys the
    // compositor's refresh-rate policy reads; this is the key many single-toggle "90Hz/120Hz" OEM
    // switches sit on top of. Setting a generous floor is safe: the platform clamps to whatever the
    // panel's supported modes actually allow, it won't crash on an unsupported value.
    private suspend fun applyRefreshFloor(): StepOutcome {
        // minSdk is already 30 (Android 11), so these keys are always present on a device this app runs on.
        if (!refreshRateSaved) {
            savedMinRefreshRate = sh("settings get system min_refresh_rate").output.trim()
            savedPeakRefreshRate = sh("settings get system peak_refresh_rate").output.trim()
            refreshRateSaved = true
        }
        return seq(
            "settings put system min_refresh_rate 120",
            "settings put system peak_refresh_rate 120"
        )
    }

    private suspend fun revertRefreshFloor(): StepOutcome {
        if (!refreshRateSaved) return noop("Refresh-rate floor was never changed")
        fun restoreCmd(key: String, saved: String) =
            if (saved.isBlank() || saved == "null") "settings delete system $key" else "settings put system $key $saved"
        val r = seq(restoreCmd("min_refresh_rate", savedMinRefreshRate), restoreCmd("peak_refresh_rate", savedPeakRefreshRate))
        refreshRateSaved = false
        return r
    }

    // ───────────────────────── Module 4: network ─────────────────────────

    private fun acquireWifiLock(): StepOutcome {
        val wm = appContext.getSystemService(WifiManager::class.java)
            ?: return StepOutcome(false, "WifiManager", "WifiManager unavailable")
        if (wifiLock?.isHeld != true) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ronova:lowlatency").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        @Suppress("DEPRECATION")
        val freq = try { wm.connectionInfo?.frequency ?: 0 } catch (_: Exception) { 0 }
        val band = when {
            freq in 2400..2500 -> "2.4 GHz ($freq MHz) - 5 GHz is strongly recommended"
            freq in 4900..5900 -> "5 GHz ($freq MHz)"
            freq >= 5925 -> "6 GHz ($freq MHz)"
            else -> "unknown band"
        }
        return StepOutcome(true, "WIFI_MODE_FULL_LOW_LATENCY", "Low-latency Wi-Fi lock held; connected on $band")
    }

    private fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    fun reacquireLocks(config: ConfigState) {
        if (config.networkShield.enabled && config.networkShield.lowLatencyWifiLock) acquireWifiLock()
    }

    private fun dotHost(dns: String): String? = when (val d = dns.trim()) {
        "1.1.1.1", "1.0.0.1" -> "one.one.one.one"
        "8.8.8.8", "8.8.4.4" -> "dns.google"
        "9.9.9.9", "149.112.112.112" -> "dns.quad9.net"
        else -> d.takeIf { it.contains('.') && it.any { ch -> ch.isLetter() } }
    }

    private suspend fun applyDns(c: ConfigState): StepOutcome {
        val dns = c.networkShield.customDns.trim()
        val host = dotHost(dns)
            ?: return StepOutcome(false, "private_dns", "No DNS-over-TLS hostname known for '$dns'. Use 1.1.1.1, 8.8.8.8, 9.9.9.9 or a hostname.")
        if (!dnsSaved) {
            val mode = sh("settings get global private_dns_mode").output.trim()
            val spec = sh("settings get global private_dns_specifier").output.trim()
            savedDnsMode = if (mode.isBlank() || mode == "null") "opportunistic" else mode
            savedDnsSpecifier = if (spec == "null") "" else spec
            dnsSaved = true
        }
        return seq(
            "settings put global private_dns_specifier $host",
            "settings put global private_dns_mode hostname"
        )
    }

    private suspend fun revertDns(): StepOutcome {
        if (!dnsSaved) return noop("DNS was never changed")
        val r = if (savedDnsSpecifier.isBlank()) {
            seq("settings put global private_dns_mode $savedDnsMode", "settings delete global private_dns_specifier")
        } else {
            seq("settings put global private_dns_mode $savedDnsMode", "settings put global private_dns_specifier $savedDnsSpecifier")
        }
        dnsSaved = false
        return r
    }

    // ───────────────────────── Module 5: Bluetooth / coexistence ─────────────────────────

    private fun hasGamepad(): Boolean = try {
        InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id)
            d != null && !d.isVirtual &&
                (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        }
    } catch (_: Exception) {
        false
    }

    private fun coexistence(): Pair<Int, String?> {
        val wm = appContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val freq = try { wm?.connectionInfo?.frequency ?: 0 } catch (_: Exception) { 0 }
        val btOn = try {
            appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
        val warn = if (freq in 2400..2500 && btOn && hasGamepad()) {
            "2.4 GHz Wi-Fi and a Bluetooth gamepad share the same antenna - switch Wi-Fi to 5 GHz"
        } else null
        return freq to warn
    }

    suspend fun refreshWarnings(config: ConfigState) {
        val b = config.bluetoothIsolator
        _warnings.value = if (b.enabled && b.warnOn24GhzCoexistence) listOfNotNull(coexistence().second) else emptyList()
    }

    // ───────────────────────── Step catalog ─────────────────────────

    internal val catalog: List<StepDef> = listOf(
        // Module 1
        StepDef(
            ModuleId.RENDER_SCALER, "scale", "Scaling display output", 2500, true,
            { it.renderScaler.enabled },
            { "${it.renderScaler.scalePercent}|${it.renderScaler.usePerAppGameDownscale}|${it.targetPackage}" },
            { applyScaler(it) }, { revertScaler(it) }
        ),
        // Module 2
        StepDef(
            ModuleId.CUTOUT_CRASH_GUARD, "cutoutReset", "Repairing display cutout insets", 3000, false,
            { it.cutoutCrashGuard.enabled && it.cutoutCrashGuard.forceResetCutout },
            { "on" },
            { applyCutoutReset() }, { noop("Cutout repair corrects stuck state; nothing to restore") }
        ),
        StepDef(
            ModuleId.CUTOUT_CRASH_GUARD, "trimCma", "Trimming CMA memory", 4000, false,
            { it.cutoutCrashGuard.enabled && it.cutoutCrashGuard.trimCmaMemory },
            { "on" },
            { seq("pm trim-caches 512M", "am kill-all") }, { noop("Memory trim is one-shot") }
        ),
        StepDef(
            ModuleId.CUTOUT_CRASH_GUARD, "activeBucket", "Keeping the game in the active standby bucket", 400, true,
            { it.cutoutCrashGuard.enabled && it.cutoutCrashGuard.keepTargetAppActive },
            { it.targetPackage },
            { applyActiveBucket(it) }, { revertActiveBucket() }
        ),
        StepDef(
            ModuleId.CUTOUT_CRASH_GUARD, "phantomKiller", "Disabling the phantom-process killer", 500, false,
            { it.cutoutCrashGuard.enabled && it.cutoutCrashGuard.disablePhantomProcessKiller },
            { "on" },
            { applyPhantomKillerDisable() }, { revertPhantomKillerDisable() }
        ),
        // Module 3
        StepDef(
            ModuleId.FRAME_PACING, "antiBackpressure", "Stripping VSync back-pressure (experimental)", 600, false,
            { it.framePacingFix.enabled && it.framePacingFix.antiBackpressure },
            { "on" },
            { seq("setprop debug.sf.latch_unsignaled 1", "setprop debug.sf.disable_backpressure 1") },
            { seq("setprop debug.sf.latch_unsignaled 0", "setprop debug.sf.disable_backpressure 0") }
        ),
        StepDef(
            ModuleId.FRAME_PACING, "fixedPerf", "Enabling fixed performance mode", 500, false,
            { it.framePacingFix.enabled && it.framePacingFix.fixedPerformanceMode },
            { "on" },
            { sh("cmd power set-fixed-performance-mode-enabled true") },
            { sh("cmd power set-fixed-performance-mode-enabled false") }
        ),
        StepDef(
            ModuleId.FRAME_PACING, "doze", "Whitelisting from Doze", 500, true,
            { it.framePacingFix.enabled && it.framePacingFix.dozeWhitelist },
            { it.targetPackage },
            { sh("dumpsys deviceidle whitelist +${it.targetPackage}") },
            { sh("dumpsys deviceidle whitelist -${it.targetPackage}") }
        ),
        StepDef(
            ModuleId.FRAME_PACING, "dexopt", "Cancelling background dexopt", 500, false,
            { it.framePacingFix.enabled && it.framePacingFix.cancelBgDexopt },
            { "on" },
            { sh("cmd package bg-dexopt-job --cancel") }, { noop("Dexopt job cancellation is one-shot") }
        ),
        // Module 4
        StepDef(
            ModuleId.NETWORK_SHIELD, "wifiLock", "Holding low-latency Wi-Fi lock", 300, false,
            { it.networkShield.enabled && it.networkShield.lowLatencyWifiLock },
            { "on" },
            { acquireWifiLock() }, { releaseWifiLock(); noop("Wi-Fi lock released") }
        ),
        StepDef(
            ModuleId.NETWORK_SHIELD, "scanThrottle", "Overriding Wi-Fi scan throttle", 400, false,
            { it.networkShield.enabled && it.networkShield.disableWifiScanThrottling },
            { "on" },
            { seq("settings put global wifi_scan_throttle_enabled 0", "settings put global wifi_scan_always_enabled 0") },
            { seq("settings put global wifi_scan_throttle_enabled 1", "settings put global wifi_scan_always_enabled 1") }
        ),
        StepDef(
            ModuleId.NETWORK_SHIELD, "dns", "Routing DNS to custom resolver", 600, false,
            { it.networkShield.enabled && it.networkShield.customDns.isNotBlank() },
            { it.networkShield.customDns.trim() },
            { applyDns(it) }, { revertDns() }
        ),
        StepDef(
            ModuleId.NETWORK_SHIELD, "vpnSinkhole", "Starting background packet sinkhole", 900, true,
            { it.networkShield.enabled && it.networkShield.vpnPacketSinkhole },
            { it.targetPackage + "|" + it.networkShield.customDns.trim() },
            { c -> hooks?.setVpn(c, true) ?: StepOutcome(false, "vpn", "Service hooks not attached") },
            { c -> hooks?.setVpn(c, false) ?: noop("Service not running") }
        ),
        // Module 5
        StepDef(
            ModuleId.BLUETOOTH_ISOLATOR, "bleScans", "Killing BLE background scans", 400, false,
            { it.bluetoothIsolator.enabled && it.bluetoothIsolator.killBleBackgroundScans },
            { "on" },
            { sh("settings put global ble_scan_always_enabled 0") },
            { sh("settings put global ble_scan_always_enabled 1") }
        ),
        StepDef(
            ModuleId.BLUETOOTH_ISOLATOR, "coexist", "Checking 2.4 GHz coexistence", 200, false,
            { it.bluetoothIsolator.enabled && it.bluetoothIsolator.warnOn24GhzCoexistence },
            { "on" },
            {
                val (freq, warn) = coexistence()
                _warnings.value = listOfNotNull(warn)
                StepOutcome(true, "WifiInfo.getFrequency", warn ?: "Wi-Fi at $freq MHz - no coexistence conflict")
            },
            { _warnings.value = emptyList(); noop("Warning cleared") }
        ),
        // Module 6
        StepDef(
            ModuleId.FPS_OVERLAY, "fps", "Starting FPS counter", 300, true,
            { it.fpsOverlay.enabled },
            { it.targetPackage + "|" + it.fpsOverlay.refreshIntervalMs },
            { c -> hooks?.setFpsOverlay(c, true) ?: StepOutcome(false, "fps", "Service hooks not attached") },
            { c -> hooks?.setFpsOverlay(c, false) ?: noop("Service not running") }
        ),
        // Module 7
        StepDef(
            ModuleId.TOUCH_LATENCY, "longPress", "Shortening long-press timeout", 300, false,
            { it.touchLatency.enabled && it.touchLatency.reduceLongPressTimeout },
            { "on" },
            { applyLongPressTimeout() }, { revertLongPressTimeout() }
        ),
        StepDef(
            ModuleId.TOUCH_LATENCY, "animations", "Zeroing system animation scales", 300, false,
            { it.touchLatency.enabled && it.touchLatency.disableUiAnimations },
            { "on" },
            { applyAnimationScales() }, { revertAnimationScales() }
        ),
        // Module 8
        StepDef(
            ModuleId.DISPLAY_REFRESH, "refreshFloor", "Raising the refresh-rate floor", 400, false,
            { it.displayRefresh.enabled && it.displayRefresh.forceMaxRefreshRate },
            { "on" },
            { applyRefreshFloor() }, { revertRefreshFloor() }
        ),
        // Module 9
        StepDef(
            ModuleId.THERMAL_GUARD, "thermal", "Starting thermal-headroom monitor", 300, true,
            { it.thermalGuard.enabled },
            { it.thermalGuard.warnOnThrottling.toString() },
            { c -> hooks?.setThermalGuard(c, true) ?: StepOutcome(false, "thermal", "Service hooks not attached") },
            { c -> hooks?.setThermalGuard(c, false) ?: noop("Service not running") }
        )
    )

    // ───────────────────────── Public API ─────────────────────────

    private fun eta(total: Long, done: Long, startedAt: Long): Long {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val speed = if (done > 0) elapsed.toDouble() / done else 1.0
        return ((total - done) * speed).toLong().coerceAtLeast(0L)
    }

    private suspend fun execute(def: StepDef, config: ConfigState, apply: Boolean): StepResult {
        val outcome = try {
            if (apply) def.apply(config) else def.revert(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StepOutcome(false, def.key, e.message ?: e.javaClass.simpleName)
        }
        return StepResult(
            def.module, def.key,
            if (apply) def.label else "Restoring: ${def.label}",
            outcome.command, outcome.success, outcome.output
        )
    }

    suspend fun applyAll(
        config: ConfigState,
        onProgress: (percent: Int, stepLabel: String, etaMs: Long) -> Unit
    ): ApplyReport = opMutex.withLock {
        val startedAt = SystemClock.elapsedRealtime()
        val steps = catalog.filter { it.enabled(config) }
        val total = steps.sumOf { it.estimatedMs }.coerceAtLeast(1L)
        var done = 0L
        val results = ArrayList<StepResult>()

        onProgress(0, "Preparing", total)
        for (def in steps) {
            onProgress(((done * 100) / total).toInt().coerceAtMost(99), def.label, eta(total, done, startedAt))
            results += execute(def, config, true)
            done += def.estimatedMs
        }
        onProgress(100, "Done", 0L)
        ApplyReport(results, SystemClock.elapsedRealtime() - startedAt).also { _lastReport.value = it }
    }

    suspend fun applyStep(step: DeltaStep, config: ConfigState): StepResult = opMutex.withLock {
        val def = catalog.firstOrNull { it.module == step.moduleId && it.key == step.optionKey }
            ?: return@withLock StepResult(step.moduleId, step.optionKey, step.optionKey, "(none)", false, "Unknown step")
        execute(def, config, step.apply)
    }

    /** Executes only the changed steps. Reverts read oldConfig, applies read newConfig. */
    suspend fun applyDelta(
        delta: ConfigDelta,
        oldConfig: ConfigState,
        newConfig: ConfigState,
        onProgress: (percent: Int, stepLabel: String, etaMs: Long) -> Unit = { _, _, _ -> }
    ): ApplyReport = opMutex.withLock {
        val startedAt = SystemClock.elapsedRealtime()
        val pairs = delta.steps.mapNotNull { s ->
            catalog.firstOrNull { it.module == s.moduleId && it.key == s.optionKey }?.let { it to s }
        }
        val total = pairs.sumOf { it.first.estimatedMs }.coerceAtLeast(1L)
        var done = 0L
        val results = ArrayList<StepResult>()
        for ((def, step) in pairs) {
            onProgress(((done * 100) / total).toInt().coerceAtMost(99), def.label, eta(total, done, startedAt))
            results += execute(def, if (step.apply) newConfig else oldConfig, step.apply)
            done += def.estimatedMs
        }
        onProgress(100, "Done", 0L)
        ApplyReport(results, SystemClock.elapsedRealtime() - startedAt).also { _lastReport.value = it }
    }

    suspend fun revertAll(previousConfig: ConfigState): ApplyReport = opMutex.withLock {
        val startedAt = SystemClock.elapsedRealtime()
        val results = ArrayList<StepResult>()
        for (def in catalog.asReversed()) {
            if (def.enabled(previousConfig)) results += execute(def, previousConfig, false)
        }
        releaseWifiLock()
        try {
            hooks?.setVpn(previousConfig, false)
            hooks?.setFpsOverlay(previousConfig, false)
        } catch (_: Exception) {
        }
        _warnings.value = emptyList()
        ApplyReport(results, SystemClock.elapsedRealtime() - startedAt).also { _lastReport.value = it }
    }
}
