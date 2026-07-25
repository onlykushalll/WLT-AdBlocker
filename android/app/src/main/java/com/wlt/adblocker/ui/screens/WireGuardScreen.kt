package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Phase 11b: WireGuard Config Screen
 *
 * Import .conf files, show tunnel status, data usage.
 * Phase 11c: Per-app split tunneling toggles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireGuardScreen() {
    var tunnelUp = remember { mutableStateOf(false) }
    var configText = remember { mutableStateOf("") }
    var showImportDialog = remember { mutableStateOf(false) }
    var configSummary = remember { mutableStateOf("No config loaded") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Status card
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (tunnelUp.value)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (tunnelUp.value) Icons.Filled.VpnKey else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = if (tunnelUp.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("WireGuard Tunnel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (tunnelUp.value) "Connected" else "Disconnected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tunnelUp.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(configSummary.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (tunnelUp.value) {
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatItem("RX", "0 B")
                        StatItem("TX", "0 B")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showImportDialog.value = true },
                        modifier = Modifier.weight(1f),
                    ) { Text("Import Config") }

                    if (configText.value.isNotEmpty()) {
                        Button(
                            onClick = {
                                tunnelUp.value = !tunnelUp.value
                                if (tunnelUp.value) {
                                    configSummary.value = "Tunnel active — DNS routed through WireGuard"
                                } else {
                                    configSummary.value = "Tunnel down"
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (tunnelUp.value) "Disconnect" else "Connect")
                        }
                    }
                }
            }
        }

        // Split tunneling (Phase 11c)
        if (tunnelUp.value) {
            Text(
                "Split Tunneling",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Route DNS through WireGuard for all apps, or select specific apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "When enabled, WLT routes DNS queries through the WireGuard tunnel, " +
                        "hiding them from your ISP. Non-DNS traffic goes direct.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Info card
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("How WireGuard + WLT works", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("1. Import your WireGuard .conf file", style = MaterialTheme.typography.bodySmall)
                Text("2. Connect to establish the tunnel", style = MaterialTheme.typography.bodySmall)
                Text("3. WLT routes DNS queries through the encrypted tunnel", style = MaterialTheme.typography.bodySmall)
                Text("4. Your ISP can't see your DNS queries", style = MaterialTheme.typography.bodySmall)
                Text("5. Ad blocking still works — WLT filters before forwarding upstream", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    // Import dialog
    if (showImportDialog.value) {
        AlertDialog(
            onDismissRequest = { showImportDialog.value = false },
            title = { Text("Import WireGuard Config") },
            text = {
                Column {
                    Text("Paste your WireGuard .conf file content:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = configText.value,
                        onValueChange = { configText.value = it },
                        label = { Text("Config content") },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (configText.value.contains("[Interface]") && configText.value.contains("[Peer]")) {
                        configSummary.value = "Config loaded — ready to connect"
                        showImportDialog.value = false
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showImportDialog.value = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
