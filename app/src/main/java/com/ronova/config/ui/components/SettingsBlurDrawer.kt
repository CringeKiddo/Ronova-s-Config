package com.ronova.config.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ronova.config.data.ConfigState
import com.ronova.config.data.ThemeSettings
import com.ronova.config.ui.theme.LocalRonova
import com.ronova.config.ui.theme.RonovaPalette

private const val LICENSE_TEXT =
    "© Ronova. All Rights Reserved.\n\n" +
        "Licensed under Creative Commons Attribution-NonCommercial-NoDerivatives 4.0 International " +
        "(CC BY-NC-ND 4.0).\n\n" +
        "Personal use on your own device is permitted. Redistributing or re-uploading this app, and " +
        "publishing modified, tweaked or cloned versions of it, is prohibited without written permission."

private const val WARNING_TEXT =
    "This app changes system-level settings over wireless ADB: screen size and density, " +
        "SurfaceFlinger, memory and Bluetooth/Wi-Fi behaviour. If the screen ever looks wrong, press " +
        "DISABLE. Hardware telemetry (Wi-Fi band, link speed, ping) is read only while the Configure " +
        "sheet is open and never leaves your device."

/**
 * Slides a settings panel in from the right while the dashboard behind it gets a real-time
 * blur (Android 12+) and a dark scrim. On Android 11 the blur is a no-op and only the scrim shows.
 */
@Composable
fun SettingsBlurDrawer(
    visible: Boolean,
    config: ConfigState,
    onThemeChange: (ThemeSettings) -> Unit,
    onNotificationPanelToggle: (Boolean) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    val c = LocalRonova.current
    val blurRadius by animateDpAsState(
        targetValue = if (visible) 16.dp else 0.dp,
        animationSpec = tween(300),
        label = "drawerBlur"
    )
    BackHandler(enabled = visible) { onClose() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
        ) {
            content()
        }

        AnimatedVisibility(visible = visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    )
            )
        }

        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(320)) { it } + fadeIn(tween(320)),
            exit = slideOutHorizontally(tween(260)) { it } + fadeOut(tween(260))
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.86f)
                    .widthIn(max = 400.dp)
                    .background(RonovaPalette.Background)
                    .drawBehind {
                        drawLine(
                            c.verticalBrush(),
                            Offset(0f, 0f),
                            Offset(0f, this.size.height),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CelestialStar(26.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Settings", style = MaterialTheme.typography.titleLarge, color = RonovaPalette.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(38.dp)
                            .springPress(true, 19.dp, onClose)
                            .clip(CircleShape)
                            .background(RonovaPalette.Surface)
                            .border(1.dp, RonovaPalette.Border, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close settings", tint = RonovaPalette.TextPrimary)
                    }
                }
                ConstellationDivider()

                ThemeSection(config.theme, onThemeChange)

                SilkySpringCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Mid-game panel", style = MaterialTheme.typography.titleMedium, color = RonovaPalette.TextPrimary)
                            Text(
                                "Expandable quick toggles and Re-enable inside the persistent notification.",
                                style = MaterialTheme.typography.bodySmall,
                                color = RonovaPalette.TextSecondary
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        RonovaSwitch(
                            checked = config.theme.notificationPanel,
                            onCheckedChange = onNotificationPanelToggle
                        )
                    }
                }

                SilkySpringCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PrimogemDiamond(18.dp, brush = androidx.compose.ui.graphics.SolidColor(RonovaPalette.Warning))
                        Spacer(Modifier.width(8.dp))
                        Text("Hardware telemetry and warning", style = MaterialTheme.typography.titleMedium, color = RonovaPalette.Warning)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(WARNING_TEXT, style = MaterialTheme.typography.bodySmall, color = RonovaPalette.TextSecondary)
                }

                SilkySpringCard(Modifier.fillMaxWidth()) {
                    Text("License and anti-clone notice", style = MaterialTheme.typography.titleMedium, color = RonovaPalette.TextPrimary)
                    Spacer(Modifier.size(8.dp))
                    Text(LICENSE_TEXT, style = MaterialTheme.typography.bodySmall, color = RonovaPalette.TextSecondary)
                }

                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@Composable
private fun ThemeSection(theme: ThemeSettings, onThemeChange: (ThemeSettings) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SilkySpringButton(
        onClick = { open = !open },
        filled = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        CelestialStar(18.dp, glow = false)
        Spacer(Modifier.width(10.dp))
        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Collapse theme" else "Expand theme"
        )
    }
    AnimatedVisibility(visible = open) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SilkySpringCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Gradient Mode", style = MaterialTheme.typography.titleMedium, color = RonovaPalette.TextPrimary)
                        Text(
                            "Blend two colours across buttons, switches, emblems and the ENABLE bar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = RonovaPalette.TextSecondary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    RonovaSwitch(
                        checked = theme.gradientMode,
                        onCheckedChange = { onThemeChange(theme.copy(gradientMode = it)) }
                    )
                }
            }
            SilkySpringCard(Modifier.fillMaxWidth()) {
                DualColorWheelPicker(theme = theme, onThemeChange = onThemeChange)
            }
        }
    }
}
