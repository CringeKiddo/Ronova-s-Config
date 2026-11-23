package com.ronova.config.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ronova.config.data.BluetoothIsolatorConfig
import com.ronova.config.data.ConfigState
import com.ronova.config.data.CutoutCrashGuardConfig
import com.ronova.config.data.DisplayRefreshConfig
import com.ronova.config.data.FramePacingConfig
import com.ronova.config.data.FpsOverlayConfig
import com.ronova.config.data.ModuleId
import com.ronova.config.data.NetworkShieldConfig
import com.ronova.config.data.RenderScalerConfig
import com.ronova.config.data.ThermalGuardConfig
import com.ronova.config.data.TouchLatencyConfig
import com.ronova.config.data.isModuleEnabled
import com.ronova.config.data.withModuleEnabled
import com.ronova.config.ui.theme.LocalRonova
import com.ronova.config.ui.theme.RonovaPalette
import com.ronova.config.ui.theme.ronovaFieldColors
import java.util.Locale

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: ImageBitmap? = null,
    val isGame: Boolean = false
)

data class DiagnosticsState(
    val wifiBand: String = "--",
    val linkMbps: Int = 0,
    val jitterMs: Float? = null,
    val frequencyMhz: Int = 0,
    val pingMs: Float? = null
)

private fun moduleBlurb(id: ModuleId): String = when (id) {
    ModuleId.RENDER_SCALER -> "Renders fewer pixels, stretched edge to edge"
    ModuleId.CUTOUT_CRASH_GUARD -> "Fixes stuck cutout insets and WebView crashes"
    ModuleId.FRAME_PACING -> "Removes VSync back-pressure for lower input lag"
    ModuleId.NETWORK_SHIELD -> "Wi-Fi lock, DNS and background traffic control"
    ModuleId.BLUETOOTH_ISOLATOR -> "Keeps gamepad traffic clear of Wi-Fi interference"
    ModuleId.FPS_OVERLAY -> "2-3 digit FPS readout straight from SurfaceFlinger"
    ModuleId.TOUCH_LATENCY -> "Shorter long-press timeout, zero system animation delay"
    ModuleId.DISPLAY_REFRESH -> "Keeps the panel off a low refresh mode the game never asked for"
    ModuleId.THERMAL_GUARD -> "Warns before throttling hits, straight from PowerManager"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureSheet(
    config: ConfigState,
    apps: List<AppEntry>,
    diagnostics: DiagnosticsState,
    onConfigChange: (ConfigState) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RonovaPalette.Background,
        contentColor = RonovaPalette.TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = RonovaPalette.Border) }
    ) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Configure")
                    ConstellationDivider()
                }
            }
            item {
                TargetAppCard(config.targetPackage, apps) { pickerOpen = true }
            }
            item { DiagnosticStrip(diagnostics) }
            items(ModuleId.entries.toList(), key = { it.name }) { id ->
                val on = config.isModuleEnabled(id)
                ModuleCard(
                    id = id,
                    enabled = on,
                    expanded = expanded == id.name,
                    onEnabledChange = { onConfigChange(config.withModuleEnabled(id, it)) },
                    onToggleExpanded = { expanded = if (expanded == id.name) null else id.name }
                ) {
                    when (id) {
                        ModuleId.RENDER_SCALER -> ScalerSettings(config.renderScaler, on) {
                            onConfigChange(config.copy(renderScaler = it))
                        }
                        ModuleId.CUTOUT_CRASH_GUARD -> CutoutSettings(config.cutoutCrashGuard, on) {
                            onConfigChange(config.copy(cutoutCrashGuard = it))
                        }
                        ModuleId.FRAME_PACING -> PacingSettings(config.framePacingFix, on) {
                            onConfigChange(config.copy(framePacingFix = it))
                        }
                        ModuleId.NETWORK_SHIELD -> NetworkSettings(config.networkShield, on) {
                            onConfigChange(config.copy(networkShield = it))
                        }
                        ModuleId.BLUETOOTH_ISOLATOR -> BluetoothSettings(config.bluetoothIsolator, on) {
                            onConfigChange(config.copy(bluetoothIsolator = it))
                        }
                        ModuleId.FPS_OVERLAY -> FpsSettings(config.fpsOverlay, on) {
                            onConfigChange(config.copy(fpsOverlay = it))
                        }
                        ModuleId.TOUCH_LATENCY -> TouchLatencySettings(config.touchLatency, on) {
                            onConfigChange(config.copy(touchLatency = it))
                        }
                        ModuleId.DISPLAY_REFRESH -> DisplayRefreshSettings(config.displayRefresh, on) {
                            onConfigChange(config.copy(displayRefresh = it))
                        }
                        ModuleId.THERMAL_GUARD -> ThermalGuardSettings(config.thermalGuard, on) {
                            onConfigChange(config.copy(thermalGuard = it))
                        }
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        AppPickerDialog(
            apps = apps,
            selected = config.targetPackage,
            onSelect = {
                onConfigChange(config.copy(targetPackage = it))
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false }
        )
    }
}

// ───────────────────────── Target app ─────────────────────────

@Composable
private fun TargetAppCard(targetPackage: String, apps: List<AppEntry>, onChange: () -> Unit) {
    val entry = apps.firstOrNull { it.packageName == targetPackage }
    val label = entry?.label ?: if (targetPackage == "com.nvidia.geforcenow") "GeForce NOW" else targetPackage
    SilkySpringCard(Modifier.fillMaxWidth()) {
        Text("TARGET GAME", style = MaterialTheme.typography.labelMedium, color = RonovaPalette.TextSecondary)
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(entry?.icon, 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = RonovaPalette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(targetPackage, style = MaterialTheme.typography.bodySmall, color = RonovaPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            SilkySpringButton(onClick = onChange, filled = false, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Text("Change", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, sizeDp: Int) {
    val shape = RoundedCornerShape(10.dp)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(sizeDp.dp).clip(shape)
        )
    } else {
        Box(
            Modifier.size(sizeDp.dp).clip(shape).background(RonovaPalette.Border),
            contentAlignment = Alignment.Center
        ) {
            CelestialStar((sizeDp * 0.6f).dp, glow = false)
        }
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<AppEntry>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalRonova.current
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) apps
        else apps.filter { it.label.lowercase(Locale.getDefault()).contains(q) || it.packageName.lowercase(Locale.getDefault()).contains(q) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(RonovaPalette.Surface)
                .border(1.dp, c.borderBrush(0.5f), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            SectionTitle("Choose target game")
            Spacer(Modifier.size(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search installed apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = c.primary) },
                colors = ronovaFieldColors(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.size(8.dp))
            if (filtered.isEmpty()) {
                Text(
                    "No matching apps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RonovaPalette.TextSecondary,
                    modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth()
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        val isSel = app.packageName == selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSel) c.primary.copy(alpha = 0.14f) else Color.Transparent)
                                .clickable { onSelect(app.packageName) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(app.icon, 36)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge, color = RonovaPalette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = RonovaPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (app.isGame) {
                                PrimogemDiamond(16.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── Diagnostics ─────────────────────────

@Composable
private fun DiagnosticStrip(d: DiagnosticsState) {
    val bandColor = when {
        d.wifiBand.startsWith("2.4") -> RonovaPalette.Warning
        d.wifiBand == "--" || d.wifiBand == "No Wi-Fi" -> RonovaPalette.TextSecondary
        else -> RonovaPalette.Success
    }
    SilkySpringCard(Modifier.fillMaxWidth()) {
        Text("LIVE DIAGNOSTICS", style = MaterialTheme.typography.labelMedium, color = RonovaPalette.TextSecondary)
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiagCell("Wi-Fi", d.wifiBand, bandColor, Modifier.weight(1f))
            DiagCell("Link", if (d.linkMbps > 0) "${d.linkMbps} Mbps" else "--", RonovaPalette.TextPrimary, Modifier.weight(1f))
            DiagCell(
                "Jitter",
                d.jitterMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--",
                when {
                    d.jitterMs == null -> RonovaPalette.TextSecondary
                    d.jitterMs > 8f -> RonovaPalette.Danger
                    d.jitterMs > 3f -> RonovaPalette.Warning
                    else -> RonovaPalette.Success
                },
                Modifier.weight(1f)
            )
        }
        if (d.pingMs != null) {
            Spacer(Modifier.size(8.dp))
            Text(
                "Router ping ${String.format(Locale.US, "%.1f", d.pingMs)} ms",
                style = MaterialTheme.typography.bodySmall,
                color = RonovaPalette.TextSecondary
            )
        }
        if (d.wifiBand.startsWith("2.4")) {
            Spacer(Modifier.size(6.dp))
            Text(
                "2.4 GHz is crowded and shares an antenna with Bluetooth. Switch to 5 GHz for cloud gaming.",
                style = MaterialTheme.typography.bodySmall,
                color = RonovaPalette.Warning
            )
        }
    }
}

@Composable
private fun DiagCell(title: String, value: String, valueColor: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(RonovaPalette.Background)
            .border(1.dp, RonovaPalette.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = RonovaPalette.TextSecondary)
        Spacer(Modifier.size(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, maxLines = 1)
    }
}

// ───────────────────────── Module card ─────────────────────────

@Composable
private fun ModuleCard(
    id: ModuleId,
    enabled: Boolean,
    expanded: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit
) {
    val c = LocalRonova.current
    SilkySpringCard(Modifier.fillMaxWidth(), ornate = false) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(RonovaPalette.Background)
                    .border(1.dp, if (enabled) c.borderBrush(0.6f) else SolidColor(RonovaPalette.Border), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                ModuleGlyph(id, 28.dp, enabled)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(id.title, style = MaterialTheme.typography.titleMedium, color = RonovaPalette.TextPrimary)
                Text(moduleBlurb(id), style = MaterialTheme.typography.bodySmall, color = RonovaPalette.TextSecondary)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = RonovaPalette.TextSecondary
            )
            Spacer(Modifier.width(6.dp))
            RonovaSwitch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ConstellationDivider()
                content()
            }
        }
    }
}

@Composable
private fun SubToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    hint: String? = null
) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = RonovaPalette.TextPrimary)
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall, color = RonovaPalette.TextSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        RonovaSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SelectChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalRonova.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .springPress(enabled, 12.dp, onClick)
            .clip(shape)
            .background(if (selected) c.horizontalBrush() else SolidColor(RonovaPalette.Background))
            .border(1.dp, if (selected) SolidColor(Color.Transparent) else SolidColor(RonovaPalette.Border), shape)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) c.onAccent else RonovaPalette.TextPrimary
        )
    }
}

// ───────────────────────── Per-module sub-settings ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScalerSettings(cfg: RenderScalerConfig, enabled: Boolean, onChange: (RenderScalerConfig) -> Unit) {
    val steps = listOf(
        100 to "100% Native",
        85 to "85% Balanced",
        75 to "75% Performance",
        65 to "65% High FPS",
        55 to "55% Ultra"
    )
    Text("Render quality", style = MaterialTheme.typography.labelMedium, color = RonovaPalette.TextSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { (pct, label) ->
            SelectChip(label, cfg.scalePercent == pct, enabled) { onChange(cfg.copy(scalePercent = pct)) }
        }
    }
    Text(
        "Lowers wm size and density together, so the picture still fills the screen with no black bars.",
        style = MaterialTheme.typography.bodySmall,
        color = RonovaPalette.TextSecondary
    )
    SubToggle(
        "Per-app game downscale",
        cfg.usePerAppGameDownscale,
        enabled,
        { onChange(cfg.copy(usePerAppGameDownscale = it)) },
        hint = "cmd game set --downscale (Android 14+)"
    )
}

@Composable
private fun CutoutSettings(cfg: CutoutCrashGuardConfig, enabled: Boolean, onChange: (CutoutCrashGuardConfig) -> Unit) {
    SubToggle("Force cutout inset reset", cfg.forceResetCutout, enabled, { onChange(cfg.copy(forceResetCutout = it)) })
    SubToggle(
        "Keep game in the active standby bucket",
        cfg.keepTargetAppActive,
        enabled,
        { onChange(cfg.copy(keepTargetAppActive = it)) },
        hint = "am set-standby-bucket (no root; replaces the old LMKD write)"
    )
    SubToggle(
        "Disable phantom-process killer",
        cfg.disablePhantomProcessKiller,
        enabled,
        { onChange(cfg.copy(disablePhantomProcessKiller = it)) },
        hint = "Android 12+ kills background child/WebView processes above a low cap"
    )
    SubToggle("Trim CMA memory", cfg.trimCmaMemory, enabled, { onChange(cfg.copy(trimCmaMemory = it)) })
}

@Composable
private fun PacingSettings(cfg: FramePacingConfig, enabled: Boolean, onChange: (FramePacingConfig) -> Unit) {
    SubToggle("Strip VSync back-pressure", cfg.antiBackpressure, enabled, { onChange(cfg.copy(antiBackpressure = it)) })
    SubToggle("Fixed performance mode", cfg.fixedPerformanceMode, enabled, { onChange(cfg.copy(fixedPerformanceMode = it)) })
    SubToggle("Doze whitelist", cfg.dozeWhitelist, enabled, { onChange(cfg.copy(dozeWhitelist = it)) })
    SubToggle("Cancel background dexopt", cfg.cancelBgDexopt, enabled, { onChange(cfg.copy(cancelBgDexopt = it)) })
}

@Composable
private fun NetworkSettings(cfg: NetworkShieldConfig, enabled: Boolean, onChange: (NetworkShieldConfig) -> Unit) {
    val presets = listOf("1.1.1.1", "8.8.8.8")
    var customMode by rememberSaveable { mutableStateOf(cfg.customDns !in presets) }
    SubToggle("Low-latency Wi-Fi lock", cfg.lowLatencyWifiLock, enabled, { onChange(cfg.copy(lowLatencyWifiLock = it)) })
    SubToggle(
        "Local VPN packet sinkhole",
        cfg.vpnPacketSinkhole,
        enabled,
        { onChange(cfg.copy(vpnPacketSinkhole = it)) },
        hint = "Drops background app traffic; the game and this app are exempt"
    )
    SubToggle("Disable Wi-Fi scan throttling", cfg.disableWifiScanThrottling, enabled, { onChange(cfg.copy(disableWifiScanThrottling = it)) })
    Text("DNS", style = MaterialTheme.typography.labelMedium, color = RonovaPalette.TextSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { ip ->
            SelectChip(ip, !customMode && cfg.customDns == ip, enabled) {
                customMode = false
                onChange(cfg.copy(customDns = ip))
            }
        }
        SelectChip("Custom", customMode, enabled) { customMode = true }
    }
    if (customMode) {
        OutlinedTextField(
            value = if (cfg.customDns in presets) "" else cfg.customDns,
            onValueChange = { onChange(cfg.copy(customDns = it.trim())) },
            singleLine = true,
            enabled = enabled,
            label = { Text("DNS-over-TLS hostname") },
            placeholder = { Text("dns.quad9.net") },
            colors = ronovaFieldColors(),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BluetoothSettings(cfg: BluetoothIsolatorConfig, enabled: Boolean, onChange: (BluetoothIsolatorConfig) -> Unit) {
    SubToggle("Kill background BLE scans", cfg.killBleBackgroundScans, enabled, { onChange(cfg.copy(killBleBackgroundScans = it)) })
    SubToggle("2.4 GHz coexistence alert", cfg.warnOn24GhzCoexistence, enabled, { onChange(cfg.copy(warnOn24GhzCoexistence = it)) })
    Text(
        "Boosting the Bluetooth daemon's priority needed root and had no non-root equivalent, so it was removed.",
        style = MaterialTheme.typography.bodySmall,
        color = RonovaPalette.TextSecondary
    )
}

@Composable
private fun FpsSettings(cfg: FpsOverlayConfig, enabled: Boolean, onChange: (FpsOverlayConfig) -> Unit) {
    SubToggle(
        "Show FPS counter",
        cfg.enabled,
        true,
        { onChange(cfg.copy(enabled = it)) },
        hint = "Reads real frame timestamps via dumpsys SurfaceFlinger. Needs Display over other apps permission."
    )
    Text(
        "Off by default. The overlay is click-through and has no background box.",
        style = MaterialTheme.typography.bodySmall,
        color = RonovaPalette.TextSecondary
    )
}

@Composable
private fun TouchLatencySettings(cfg: TouchLatencyConfig, enabled: Boolean, onChange: (TouchLatencyConfig) -> Unit) {
    SubToggle(
        "Shorten long-press timeout",
        cfg.reduceLongPressTimeout,
        enabled,
        { onChange(cfg.copy(reduceLongPressTimeout = it)) },
        hint = "400ms -> 250ms before a touch registers as a long-press"
    )
    SubToggle(
        "Zero system animation scales",
        cfg.disableUiAnimations,
        enabled,
        { onChange(cfg.copy(disableUiAnimations = it)) },
        hint = "Window, transition and animator duration scale -> 0"
    )
    Text(
        "Affects system UI only (dialogs, app switches, the shade), not in-game rendering - there's no public, " +
            "non-root hook into another app's touch pipeline.",
        style = MaterialTheme.typography.bodySmall,
        color = RonovaPalette.TextSecondary
    )
}

@Composable
private fun DisplayRefreshSettings(cfg: DisplayRefreshConfig, enabled: Boolean, onChange: (DisplayRefreshConfig) -> Unit) {
    SubToggle(
        "Force the refresh-rate floor up",
        cfg.forceMaxRefreshRate,
        enabled,
        { onChange(cfg.copy(forceMaxRefreshRate = it)) },
        hint = "Settings.System.min/peak_refresh_rate (Android 11+); the OS clamps to what the panel supports"
    )
    Text(
        "Fixes games that never request a high refresh rate themselves and get stuck on the device's lowest mode.",
        style = MaterialTheme.typography.bodySmall,
        color = RonovaPalette.TextSecondary
    )
}

@Composable
private fun ThermalGuardSettings(cfg: ThermalGuardConfig, enabled: Boolean, onChange: (ThermalGuardConfig) -> Unit) {
    SubToggle(
        "Warn before severe throttling",
        cfg.warnOnThrottling,
        enabled,
        { onChange(cfg.copy(warnOnThrottling = it)) },
        hint = "PowerManager.getThermalHeadroom() in-process - no ADB, no root"
    )
    Text(
        "Shows up in the notification text when the device is approaching or already in severe thermal throttling.",
        style = MaterialTheme.typography.bodySmall,
        color = RonovaPalette.TextSecondary
    )
}
