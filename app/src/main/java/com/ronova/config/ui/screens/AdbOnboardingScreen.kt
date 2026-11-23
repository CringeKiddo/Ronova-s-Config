package com.ronova.config.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ronova.config.adb.AdbConnectionState
import com.ronova.config.adb.AdbDiscoveryState
import com.ronova.config.ui.components.CelestialStar
import com.ronova.config.ui.components.ConstellationDivider
import com.ronova.config.ui.components.SilkySpringButton
import com.ronova.config.ui.components.SilkySpringCard
import com.ronova.config.ui.components.StarfieldBackground
import com.ronova.config.ui.theme.LocalRonova
import com.ronova.config.ui.theme.RonovaPalette
import com.ronova.config.ui.theme.ronovaFieldColors

private enum class Tone { Success, Info, Warning, Error }

@Composable
fun AdbOnboardingScreen(
    discovery: AdbDiscoveryState,
    connection: AdbConnectionState,
    onOpenDevSettings: () -> Unit,
    onShowPairingNotification: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    pairingBusy: Boolean = false,
    pairingMessage: String? = null,
    onContinue: () -> Unit = onSkip
) {
    var code by rememberSaveable { mutableStateOf("") }
    val connected = connection is AdbConnectionState.Connected

    StarfieldBackground(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CelestialStar(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Wireless ADB Setup", style = MaterialTheme.typography.headlineMedium, color = RonovaPalette.TextPrimary)
                        Text(
                            "One-time pairing. It stays trusted after reboots.",
                            style = MaterialTheme.typography.bodySmall,
                            color = RonovaPalette.TextSecondary
                        )
                    }
                }
                ConstellationDivider()

                StepCard(1, "Open Wireless debugging", "Turn on Developer options, then Wireless debugging, and tap Pair device with pairing code.") {
                    SilkySpringButton(onClick = onOpenDevSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Developer Options", style = MaterialTheme.typography.labelLarge)
                    }
                }

                StepCard(2, "Wait for the port", "This screen finds your phone's pairing port automatically.") {
                    val (label, tone) = statusFor(discovery, connection)
                    StatusBadge(label, tone, searching = discovery.isScanning && !connected && discovery.pairingEndpoint == null)
                }

                StepCard(3, "Enter the 6-digit code", "Type the code shown in the pairing dialog, or reply from the notification so you can keep the dialog open.") {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                        singleLine = true,
                        label = { Text("6-digit pairing code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = ronovaFieldColors(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SilkySpringButton(
                            onClick = { onSubmitCode(code) },
                            enabled = code.length == 6 && !pairingBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (pairingBusy) "Pairing..." else "Pair Now", style = MaterialTheme.typography.labelLarge)
                        }
                        SilkySpringButton(
                            onClick = onShowPairingNotification,
                            filled = false,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use notification", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (pairingMessage != null) {
                        Spacer(Modifier.size(10.dp))
                        Text(
                            pairingMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pairingMessage.startsWith("Paired") || pairingMessage == "Pairing...") RonovaPalette.Success else RonovaPalette.Warning
                        )
                    }
                }
            }

            Spacer(Modifier.size(12.dp))
            if (connected) {
                SilkySpringButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue to Dashboard", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.size(10.dp))
            }
            SilkySpringButton(
                onClick = onSkip,
                filled = false,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Text(
                    "Skip for Now",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun statusFor(discovery: AdbDiscoveryState, connection: AdbConnectionState): Pair<String, Tone> = when {
    connection is AdbConnectionState.Connected ->
        "ADB Connected  ${connection.host}:${connection.port}" to Tone.Success
    connection is AdbConnectionState.Connecting -> "Connecting..." to Tone.Info
    discovery.pairingEndpoint != null ->
        "Pairing Port Detected  :${discovery.pairingEndpoint.port}" to Tone.Success
    discovery.connectEndpoint != null ->
        "Wireless debugging is on (port ${discovery.connectEndpoint.port}). Tap Pair device with pairing code." to Tone.Info
    discovery.error != null -> discovery.error to Tone.Error
    discovery.isScanning -> "Searching..." to Tone.Warning
    else -> "Not searching yet" to Tone.Info
}

@Composable
private fun StepCard(number: Int, title: String, body: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalRonova.current
    SilkySpringCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = this.size.width
                    val p = Path()
                    p.moveTo(w / 2f, 1.dp.toPx())
                    p.lineTo(w - 1.dp.toPx(), w / 2f)
                    p.lineTo(w / 2f, w - 1.dp.toPx())
                    p.lineTo(1.dp.toPx(), w / 2f)
                    p.close()
                    drawPath(p, c.brush(), alpha = 0.18f)
                    drawPath(p, c.brush(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
                }
                Text("$number", style = MaterialTheme.typography.titleMedium, color = c.primary)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = RonovaPalette.TextPrimary)
        }
        Spacer(Modifier.size(8.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = RonovaPalette.TextSecondary)
        Spacer(Modifier.size(12.dp))
        content()
    }
}

@Composable
private fun StatusBadge(label: String, tone: Tone, searching: Boolean) {
    val color = when (tone) {
        Tone.Success -> RonovaPalette.Success
        Tone.Info -> LocalRonova.current.primary
        Tone.Warning -> RonovaPalette.Warning
        Tone.Error -> RonovaPalette.Danger
    }
    val pulse = rememberInfiniteTransition(label = "badge")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "badgeAlpha"
    )
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = if (searching) alpha else 1f))
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
