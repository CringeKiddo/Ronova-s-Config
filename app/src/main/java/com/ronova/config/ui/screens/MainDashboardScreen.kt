package com.ronova.config.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.ronova.config.data.ConfigState
import com.ronova.config.data.EngineRuntimeState
import com.ronova.config.data.ModuleId
import com.ronova.config.data.isModuleEnabled
import com.ronova.config.ui.components.CelestialStar
import com.ronova.config.ui.components.ConstellationDivider
import com.ronova.config.ui.components.ModuleGlyph
import com.ronova.config.ui.components.OrbitalRing
import com.ronova.config.ui.components.SilkySpringButton
import com.ronova.config.ui.components.StarfieldBackground
import com.ronova.config.ui.components.springPress
import com.ronova.config.ui.theme.LocalRonova
import com.ronova.config.ui.theme.RonovaPalette
import java.util.Locale

@Composable
fun MainDashboardScreen(
    config: ConfigState,
    runtime: EngineRuntimeState,
    adbReady: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onConfigure: () -> Unit,
    onSettings: () -> Unit,
    onAdbSetup: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    pendingChanges: Int = 0,
    onApplyChanges: () -> Unit = {}
) {
    val c = LocalRonova.current
    val isActive = runtime is EngineRuntimeState.Active
    val ringProgress = when (runtime) {
        is EngineRuntimeState.Starting -> runtime.percent / 100f
        is EngineRuntimeState.Active -> 1f
        else -> 0f
    }

    val breathe = rememberInfiniteTransition(label = "crest")
    val crestScale by breathe.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "crestScale"
    )

    StarfieldBackground(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CelestialStar(30.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Ronova's Config",
                    style = MaterialTheme.typography.titleLarge,
                    color = RonovaPalette.TextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .springPress(true, 22.dp, onSettings)
                        .clip(CircleShape)
                        .background(RonovaPalette.Surface)
                        .border(1.dp, c.borderBrush(0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = c.primary)
                }
            }

            if (!adbReady) {
                Spacer(Modifier.size(12.dp))
                AdbSetupPill(onAdbSetup)
            }

            Spacer(Modifier.weight(1f))

            // Crest
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.scale(crestScale).alpha(if (isActive) 1f else 0.78f)) {
                    CelestialStar(132.dp)
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    "LATENCY  -  FRAME PACING  -  DISPLAY",
                    style = MaterialTheme.typography.labelMedium,
                    color = RonovaPalette.TextSecondary
                )
                Spacer(Modifier.size(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ModuleId.entries.forEach { id ->
                        ModuleGlyph(id, 22.dp, config.isModuleEnabled(id) && isActive)
                    }
                }
                Spacer(Modifier.size(10.dp))
                ConstellationDivider(Modifier.padding(horizontal = 24.dp))
                Text(
                    config.targetPackage,
                    style = MaterialTheme.typography.bodySmall,
                    color = RonovaPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.weight(1f))

            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.contains("failed", ignoreCase = true) || message.contains("cannot", ignoreCase = true))
                        RonovaPalette.Warning else RonovaPalette.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                )
            }

            if (isActive && pendingChanges > 0) {
                SilkySpringButton(onClick = onApplyChanges, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Apply $pendingChanges change${if (pendingChanges == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.size(10.dp))
            }

            // Configure sits directly above the master control
            SilkySpringButton(
                onClick = onConfigure,
                filled = false,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text("CONFIGURE", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.size(12.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OrbitalRing(
                    progress = ringProgress,
                    active = isActive,
                    modifier = Modifier.weight(1f).height(88.dp)
                ) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        MasterButton(runtime, onEnable, onDisable, Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.width(12.dp))
                StatusPill(runtime)
            }
            Spacer(Modifier.size(6.dp))
        }
    }
}

@Composable
private fun AdbSetupPill(onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "adbPill")
    val glow by pulse.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "adbPillGlow"
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .alpha(glow)
                .springPress(true, 24.dp, onClick)
                .clip(RoundedCornerShape(50))
                .background(RonovaPalette.Warning)
                .padding(horizontal = 22.dp, vertical = 11.dp)
        ) {
            Text(
                "ADB SETUP REQUIRED",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp),
                color = RonovaPalette.OnAccent
            )
        }
    }
}

@Composable
private fun MasterButton(
    runtime: EngineRuntimeState,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier
) {
    val c = LocalRonova.current
    val shape = RoundedCornerShape(18.dp)
    val titleStyle = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 3.sp
    )
    when (runtime) {
        is EngineRuntimeState.Starting -> {
            val fraction by animateFloatAsState(
                targetValue = (runtime.percent / 100f).coerceIn(0f, 1f),
                animationSpec = tween(380, easing = FastOutSlowInEasing),
                label = "enableProgress"
            )
            Box(
                modifier
                    .clip(shape)
                    .background(RonovaPalette.Surface)
                    .border(1.dp, RonovaPalette.Border, shape)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(c.horizontalBrush())
                )
                val eta = if (runtime.etaMs > 0L) String.format(Locale.US, "~%.1fs", runtime.etaMs / 1000f) else ""
                val textStyle = MaterialTheme.typography.labelLarge.copy(
                    shadow = Shadow(Color.Black.copy(alpha = 0.85f), blurRadius = 6f)
                )
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${runtime.percent}%", style = textStyle, color = Color.White, maxLines = 1)
                        Text(
                            runtime.stepLabel,
                            style = MaterialTheme.typography.bodySmall.copy(shadow = Shadow(Color.Black.copy(alpha = 0.85f), blurRadius = 6f)),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (eta.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(eta, style = textStyle, color = Color.White, maxLines = 1)
                    }
                }
            }
        }
        EngineRuntimeState.Idle -> {
            Box(
                modifier
                    .springPress(true, 18.dp, onEnable)
                    .clip(shape)
                    .background(c.horizontalBrush()),
                contentAlignment = Alignment.Center
            ) {
                Text("ENABLE", style = titleStyle, color = c.onAccent)
            }
        }
        EngineRuntimeState.Active -> {
            Box(
                modifier
                    .springPress(true, 18.dp, onDisable)
                    .clip(shape)
                    .background(RonovaPalette.Surface)
                    .border(1.5.dp, c.borderBrush(0.9f), shape),
                contentAlignment = Alignment.Center
            ) {
                Text("DISABLE", style = titleStyle, color = c.textPrimary)
            }
        }
        EngineRuntimeState.Stopping -> {
            Box(
                modifier
                    .clip(shape)
                    .background(RonovaPalette.Surface)
                    .border(1.dp, RonovaPalette.Border, shape),
                contentAlignment = Alignment.Center
            ) {
                Text("RESTORING...", style = MaterialTheme.typography.labelLarge, color = RonovaPalette.TextSecondary)
            }
        }
    }
}

@Composable
private fun StatusPill(runtime: EngineRuntimeState) {
    val c = LocalRonova.current
    val label = when (runtime) {
        EngineRuntimeState.Idle -> "OFF"
        is EngineRuntimeState.Starting -> "STARTING"
        EngineRuntimeState.Active -> "ACTIVE"
        EngineRuntimeState.Stopping -> "STOPPING"
    }
    val dot = when (runtime) {
        EngineRuntimeState.Active -> RonovaPalette.Success
        EngineRuntimeState.Idle -> RonovaPalette.TextSecondary
        else -> RonovaPalette.Warning
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .clip(shape)
            .background(RonovaPalette.Surface)
            .border(1.dp, if (runtime == EngineRuntimeState.Active) c.borderBrush(0.7f) else androidx.compose.ui.graphics.SolidColor(RonovaPalette.Border), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("STATUS:", style = MaterialTheme.typography.labelMedium, color = RonovaPalette.TextSecondary)
        Spacer(Modifier.size(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = RonovaPalette.TextPrimary)
        }
    }
}
