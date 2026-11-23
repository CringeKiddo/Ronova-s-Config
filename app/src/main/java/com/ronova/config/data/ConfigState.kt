package com.ronova.config.data

import android.graphics.Color
import org.json.JSONObject

enum class ModuleId(val shortName: String, val title: String) {
    RENDER_SCALER("Scaler", "Full-Screen Render Quality Scaler"),
    CUTOUT_CRASH_GUARD("Cutout", "Display Cutout & WebView Crash Guard"),
    FRAME_PACING("Pacing", "Frame-Pacing & Input Latency Fix"),
    NETWORK_SHIELD("Network", "Network & Wi-Fi Jitter Shield"),
    BLUETOOTH_ISOLATOR("Bluetooth", "Bluetooth & 2.4 GHz Isolator"),
    FPS_OVERLAY("FPS", "Zero-Lag FPS Counter")
}

data class RenderScalerConfig(
    val enabled: Boolean = true,
    val scalePercent: Int = 100,
    val usePerAppGameDownscale: Boolean = true
) {
    companion object {
        val SCALE_OPTIONS = listOf(100, 85, 75, 65, 55)
    }
}

data class CutoutCrashGuardConfig(
    val enabled: Boolean = true,
    val forceResetCutout: Boolean = true,
    val protectWebViewLmkd: Boolean = true,
    val disableHwOverlays: Boolean = true,
    val trimCmaMemory: Boolean = true
)

data class FramePacingConfig(
    val enabled: Boolean = true,
    val antiBackpressure: Boolean = true,
    val fixedPerformanceMode: Boolean = true,
    val dozeWhitelist: Boolean = true,
    val cancelBgDexopt: Boolean = true
)

data class NetworkShieldConfig(
    val enabled: Boolean = true,
    val lowLatencyWifiLock: Boolean = true,
    val vpnPacketSinkhole: Boolean = false,
    val customDns: String = "1.1.1.1",
    val disableWifiScanThrottling: Boolean = true
)

data class BluetoothIsolatorConfig(
    val enabled: Boolean = true,
    val killBleBackgroundScans: Boolean = true,
    val boostBluetoothDaemonPriority: Boolean = true,
    val warnOn24GhzCoexistence: Boolean = true
)

data class FpsOverlayConfig(
    val enabled: Boolean = false,
    val refreshIntervalMs: Long = 500L
)

data class ThemeSettings(
    val hue: Float = 43f,
    val saturation: Float = 0.55f,
    val brightness: Float = 0.95f,
    val gradientMode: Boolean = false,
    val hue2: Float = 198f,
    val saturation2: Float = 0.6f,
    val brightness2: Float = 0.95f,
    val notificationPanel: Boolean = true
) {
    fun primaryColorInt(): Int = Color.HSVToColor(
        floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    )

    fun secondaryColorInt(): Int = Color.HSVToColor(
        floatArrayOf(hue2.coerceIn(0f, 360f), saturation2.coerceIn(0f, 1f), brightness2.coerceIn(0f, 1f))
    )
}

data class ConfigState(
    val targetPackage: String = "com.nvidia.geforcenow",
    val isEnabled: Boolean = false,
    val renderScaler: RenderScalerConfig = RenderScalerConfig(),
    val cutoutCrashGuard: CutoutCrashGuardConfig = CutoutCrashGuardConfig(),
    val framePacingFix: FramePacingConfig = FramePacingConfig(),
    val networkShield: NetworkShieldConfig = NetworkShieldConfig(),
    val bluetoothIsolator: BluetoothIsolatorConfig = BluetoothIsolatorConfig(),
    val fpsOverlay: FpsOverlayConfig = FpsOverlayConfig(),
    val theme: ThemeSettings = ThemeSettings()
)

sealed interface EngineRuntimeState {
    data object Idle : EngineRuntimeState
    data class Starting(val percent: Int, val stepLabel: String, val etaMs: Long) : EngineRuntimeState
    data object Active : EngineRuntimeState
    data object Stopping : EngineRuntimeState
}

fun ConfigState.isModuleEnabled(id: ModuleId): Boolean = when (id) {
    ModuleId.RENDER_SCALER -> renderScaler.enabled
    ModuleId.CUTOUT_CRASH_GUARD -> cutoutCrashGuard.enabled
    ModuleId.FRAME_PACING -> framePacingFix.enabled
    ModuleId.NETWORK_SHIELD -> networkShield.enabled
    ModuleId.BLUETOOTH_ISOLATOR -> bluetoothIsolator.enabled
    ModuleId.FPS_OVERLAY -> fpsOverlay.enabled
}

fun ConfigState.withModuleEnabled(id: ModuleId, enabled: Boolean): ConfigState = when (id) {
    ModuleId.RENDER_SCALER -> copy(renderScaler = renderScaler.copy(enabled = enabled))
    ModuleId.CUTOUT_CRASH_GUARD -> copy(cutoutCrashGuard = cutoutCrashGuard.copy(enabled = enabled))
    ModuleId.FRAME_PACING -> copy(framePacingFix = framePacingFix.copy(enabled = enabled))
    ModuleId.NETWORK_SHIELD -> copy(networkShield = networkShield.copy(enabled = enabled))
    ModuleId.BLUETOOTH_ISOLATOR -> copy(bluetoothIsolator = bluetoothIsolator.copy(enabled = enabled))
    ModuleId.FPS_OVERLAY -> copy(fpsOverlay = fpsOverlay.copy(enabled = enabled))
}

// ───────────────────────── JSON persistence ─────────────────────────

fun ConfigState.toJson(): String = JSONObject().apply {
    put("targetPackage", targetPackage)
    put("isEnabled", isEnabled)
    put("renderScaler", JSONObject().apply {
        put("enabled", renderScaler.enabled)
        put("scalePercent", renderScaler.scalePercent)
        put("usePerAppGameDownscale", renderScaler.usePerAppGameDownscale)
    })
    put("cutoutCrashGuard", JSONObject().apply {
        put("enabled", cutoutCrashGuard.enabled)
        put("forceResetCutout", cutoutCrashGuard.forceResetCutout)
        put("protectWebViewLmkd", cutoutCrashGuard.protectWebViewLmkd)
        put("disableHwOverlays", cutoutCrashGuard.disableHwOverlays)
        put("trimCmaMemory", cutoutCrashGuard.trimCmaMemory)
    })
    put("framePacingFix", JSONObject().apply {
        put("enabled", framePacingFix.enabled)
        put("antiBackpressure", framePacingFix.antiBackpressure)
        put("fixedPerformanceMode", framePacingFix.fixedPerformanceMode)
        put("dozeWhitelist", framePacingFix.dozeWhitelist)
        put("cancelBgDexopt", framePacingFix.cancelBgDexopt)
    })
    put("networkShield", JSONObject().apply {
        put("enabled", networkShield.enabled)
        put("lowLatencyWifiLock", networkShield.lowLatencyWifiLock)
        put("vpnPacketSinkhole", networkShield.vpnPacketSinkhole)
        put("customDns", networkShield.customDns)
        put("disableWifiScanThrottling", networkShield.disableWifiScanThrottling)
    })
    put("bluetoothIsolator", JSONObject().apply {
        put("enabled", bluetoothIsolator.enabled)
        put("killBleBackgroundScans", bluetoothIsolator.killBleBackgroundScans)
        put("boostBluetoothDaemonPriority", bluetoothIsolator.boostBluetoothDaemonPriority)
        put("warnOn24GhzCoexistence", bluetoothIsolator.warnOn24GhzCoexistence)
    })
    put("fpsOverlay", JSONObject().apply {
        put("enabled", fpsOverlay.enabled)
        put("refreshIntervalMs", fpsOverlay.refreshIntervalMs)
    })
    put("theme", JSONObject().apply {
        put("hue", theme.hue.toDouble())
        put("saturation", theme.saturation.toDouble())
        put("brightness", theme.brightness.toDouble())
        put("gradientMode", theme.gradientMode)
        put("hue2", theme.hue2.toDouble())
        put("saturation2", theme.saturation2.toDouble())
        put("brightness2", theme.brightness2.toDouble())
        put("notificationPanel", theme.notificationPanel)
    })
}.toString()

fun configFromJson(json: String?): ConfigState {
    val d = ConfigState()
    if (json.isNullOrBlank()) return d
    return try {
        val o = JSONObject(json)
        val rs = o.optJSONObject("renderScaler")
        val cg = o.optJSONObject("cutoutCrashGuard")
        val fp = o.optJSONObject("framePacingFix")
        val ns = o.optJSONObject("networkShield")
        val bt = o.optJSONObject("bluetoothIsolator")
        val fo = o.optJSONObject("fpsOverlay")
        val th = o.optJSONObject("theme")

        val rawScale = rs?.optInt("scalePercent", d.renderScaler.scalePercent) ?: d.renderScaler.scalePercent
        val scale = RenderScalerConfig.SCALE_OPTIONS.minByOrNull { kotlin.math.abs(it - rawScale) } ?: 100

        ConfigState(
            targetPackage = o.optString("targetPackage", d.targetPackage).ifBlank { d.targetPackage },
            isEnabled = o.optBoolean("isEnabled", d.isEnabled),
            renderScaler = RenderScalerConfig(
                enabled = rs?.optBoolean("enabled", d.renderScaler.enabled) ?: d.renderScaler.enabled,
                scalePercent = scale,
                usePerAppGameDownscale = rs?.optBoolean("usePerAppGameDownscale", d.renderScaler.usePerAppGameDownscale)
                    ?: d.renderScaler.usePerAppGameDownscale
            ),
            cutoutCrashGuard = CutoutCrashGuardConfig(
                enabled = cg?.optBoolean("enabled", d.cutoutCrashGuard.enabled) ?: d.cutoutCrashGuard.enabled,
                forceResetCutout = cg?.optBoolean("forceResetCutout", d.cutoutCrashGuard.forceResetCutout)
                    ?: d.cutoutCrashGuard.forceResetCutout,
                protectWebViewLmkd = cg?.optBoolean("protectWebViewLmkd", d.cutoutCrashGuard.protectWebViewLmkd)
                    ?: d.cutoutCrashGuard.protectWebViewLmkd,
                disableHwOverlays = cg?.optBoolean("disableHwOverlays", d.cutoutCrashGuard.disableHwOverlays)
                    ?: d.cutoutCrashGuard.disableHwOverlays,
                trimCmaMemory = cg?.optBoolean("trimCmaMemory", d.cutoutCrashGuard.trimCmaMemory)
                    ?: d.cutoutCrashGuard.trimCmaMemory
            ),
            framePacingFix = FramePacingConfig(
                enabled = fp?.optBoolean("enabled", d.framePacingFix.enabled) ?: d.framePacingFix.enabled,
                antiBackpressure = fp?.optBoolean("antiBackpressure", d.framePacingFix.antiBackpressure)
                    ?: d.framePacingFix.antiBackpressure,
                fixedPerformanceMode = fp?.optBoolean("fixedPerformanceMode", d.framePacingFix.fixedPerformanceMode)
                    ?: d.framePacingFix.fixedPerformanceMode,
                dozeWhitelist = fp?.optBoolean("dozeWhitelist", d.framePacingFix.dozeWhitelist)
                    ?: d.framePacingFix.dozeWhitelist,
                cancelBgDexopt = fp?.optBoolean("cancelBgDexopt", d.framePacingFix.cancelBgDexopt)
                    ?: d.framePacingFix.cancelBgDexopt
            ),
            networkShield = NetworkShieldConfig(
                enabled = ns?.optBoolean("enabled", d.networkShield.enabled) ?: d.networkShield.enabled,
                lowLatencyWifiLock = ns?.optBoolean("lowLatencyWifiLock", d.networkShield.lowLatencyWifiLock)
                    ?: d.networkShield.lowLatencyWifiLock,
                vpnPacketSinkhole = ns?.optBoolean("vpnPacketSinkhole", d.networkShield.vpnPacketSinkhole)
                    ?: d.networkShield.vpnPacketSinkhole,
                customDns = ns?.optString("customDns", d.networkShield.customDns) ?: d.networkShield.customDns,
                disableWifiScanThrottling = ns?.optBoolean("disableWifiScanThrottling", d.networkShield.disableWifiScanThrottling)
                    ?: d.networkShield.disableWifiScanThrottling
            ),
            bluetoothIsolator = BluetoothIsolatorConfig(
                enabled = bt?.optBoolean("enabled", d.bluetoothIsolator.enabled) ?: d.bluetoothIsolator.enabled,
                killBleBackgroundScans = bt?.optBoolean("killBleBackgroundScans", d.bluetoothIsolator.killBleBackgroundScans)
                    ?: d.bluetoothIsolator.killBleBackgroundScans,
                boostBluetoothDaemonPriority = bt?.optBoolean("boostBluetoothDaemonPriority", d.bluetoothIsolator.boostBluetoothDaemonPriority)
                    ?: d.bluetoothIsolator.boostBluetoothDaemonPriority,
                warnOn24GhzCoexistence = bt?.optBoolean("warnOn24GhzCoexistence", d.bluetoothIsolator.warnOn24GhzCoexistence)
                    ?: d.bluetoothIsolator.warnOn24GhzCoexistence
            ),
            fpsOverlay = FpsOverlayConfig(
                enabled = fo?.optBoolean("enabled", d.fpsOverlay.enabled) ?: d.fpsOverlay.enabled,
                refreshIntervalMs = (fo?.optLong("refreshIntervalMs", d.fpsOverlay.refreshIntervalMs)
                    ?: d.fpsOverlay.refreshIntervalMs).coerceIn(250L, 5_000L)
            ),
            theme = ThemeSettings(
                hue = th?.optDouble("hue", d.theme.hue.toDouble())?.toFloat() ?: d.theme.hue,
                saturation = th?.optDouble("saturation", d.theme.saturation.toDouble())?.toFloat() ?: d.theme.saturation,
                brightness = th?.optDouble("brightness", d.theme.brightness.toDouble())?.toFloat() ?: d.theme.brightness,
                gradientMode = th?.optBoolean("gradientMode", d.theme.gradientMode) ?: d.theme.gradientMode,
                hue2 = th?.optDouble("hue2", d.theme.hue2.toDouble())?.toFloat() ?: d.theme.hue2,
                saturation2 = th?.optDouble("saturation2", d.theme.saturation2.toDouble())?.toFloat() ?: d.theme.saturation2,
                brightness2 = th?.optDouble("brightness2", d.theme.brightness2.toDouble())?.toFloat() ?: d.theme.brightness2,
                notificationPanel = th?.optBoolean("notificationPanel", d.theme.notificationPanel) ?: d.theme.notificationPanel
            )
        )
    } catch (_: Exception) {
        d
    }
}
