package com.ronova.config.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ronova.config.ui.PermissionStatus
import com.ronova.config.ui.theme.RonovaPalette

@Composable
fun PermissionsDialog(
    status: PermissionStatus,
    onRequestRuntime: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenOverlay: () -> Unit,
    onContinue: () -> Unit
) {
    Dialog(onDismissRequest = onContinue) {
        SilkySpringCard(Modifier.fillMaxWidth()) {
            SectionTitle("Permissions")
            Spacer(Modifier.size(6.dp))
            Text(
                "Ronova's Config works best with these. You can skip any of them and grant them later.",
                style = MaterialTheme.typography.bodySmall,
                color = RonovaPalette.TextSecondary
            )
            Spacer(Modifier.size(12.dp))
            PermissionRow("Notifications", "Status panel and pairing-code reply", status.notifications, onRequestRuntime)
            PermissionRow("Location", "Needed by Android to read Wi-Fi details", status.location, onRequestRuntime)
            PermissionRow("Usage access", "Detects which game is in the foreground", status.usageStats, onOpenUsageAccess)
            PermissionRow("Display over other apps", "Shows the FPS counter", status.overlay, onOpenOverlay)
            Spacer(Modifier.size(14.dp))
            SilkySpringButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continue", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, body: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = RonovaPalette.TextPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = RonovaPalette.TextSecondary)
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Text("Granted", style = MaterialTheme.typography.labelLarge, color = RonovaPalette.Success)
        } else {
            SilkySpringButton(
                onClick = onGrant,
                filled = false,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Grant", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
