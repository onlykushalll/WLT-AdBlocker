package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen() {
    var dnsLayer by remember { mutableStateOf(true) }
    var sniLayer by remember { mutableStateOf(false) }
    var httpsLayer by remember { mutableStateOf(false) }
    var scriptletLayer by remember { mutableStateOf(false) }
    var autoUpdate by remember { mutableStateOf(true) }
    var blockResponse by remember { mutableStateOf(0) } // 0=NXDOMAIN, 1=0.0.0.0, 2=REFUSED
    var upstream by remember { mutableStateOf(0) } // 0=cloudflare, 1=google, 2=quad9
    var theme by remember { mutableStateOf(0) } // 0=system, 1=light, 2=dark

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
        }

        // Smart Cascade layers
        item {
            SettingsSectionCard("Smart Cascade Layers", Icons.Filled.Layers) {
                LayerToggleRow("DNS Layer", "Block at DNS level — 85-90% of ads/tracker", dnsLayer) { dnsLayer = it }
                LayerToggleRow("SNI Layer (Phase 2)", "Inspect TLS ClientHello without MITM — catches hardcoded-IP SDKs", sniLayer) { sniLayer = it }
                LayerToggleRow("HTTPS Layer (Phase 3)", "MITM browsers for URL rules + cosmetics", httpsLayer) { httpsLayer = it }
                LayerToggleRow("Scriptlet Layer (Phase 3)", "uBlock-style JS injection for anti-adblock + YouTube web", scriptletLayer) { scriptletLayer = it }
            }
        }

        item {
            SettingsSectionCard("Block Response", Icons.Filled.Block) {
                Text("What to return for blocked queries:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow {
                    val options = listOf("NXDOMAIN", "0.0.0.0", "REFUSED")
                    options.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = blockResponse == i,
                            onClick = { blockResponse = i },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size)
                        ) { Text(label, fontSize = 11.sp) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when (blockResponse) {
                        0 -> "NXDOMAIN: apps see 'host not found' (default, most compatible)"
                        1 -> "0.0.0.0: null IP sinkhole (AdAway-style, some apps prefer this)"
                        else -> "REFUSED: apps see 'not allowed' (HostShield-style)"
                    },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                )
            }
        }

        item {
            SettingsSectionCard("Upstream DNS", Icons.Filled.Dns) {
                Text("Encrypted DNS resolver for non-blocked queries:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow {
                    val options = listOf("Cloudflare", "Google", "Quad9")
                    options.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = upstream == i,
                            onClick = { upstream = i },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size)
                        ) { Text(label, fontSize = 11.sp) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when (upstream) {
                        0 -> "Cloudflare 1.1.1.1 — fast, privacy-respecting (default)"
                        1 -> "Google 8.8.8.8 — ubiquitous, fast"
                        else -> "Quad9 9.9.9.9 — built-in malware blocking"
                    },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.outline
                )
            }
        }

        item {
            SettingsSectionCard("Blocklist Updates", Icons.Filled.Update) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.padding(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-update every 24h", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Background refresh of all enabled lists", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoUpdate, onCheckedChange = { autoUpdate = it })
                }
                Spacer(Modifier.height(8.dp))
                Text("Last update: never (lists not yet loaded)", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
        }

        item {
            SettingsSectionCard("Theme", Icons.Filled.Palette) {
                SingleChoiceSegmentedButtonRow {
                    val options = listOf("System", "Light", "Dark")
                    options.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = theme == i,
                            onClick = { theme = i },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size)
                        ) { Text(label, fontSize = 11.sp) }
                    }
                }
            }
        }

        item {
            SettingsSectionCard("Privacy & Trust", Icons.Filled.Security) {
                Text("WLT is designed to be trustworthy by architecture, not by policy:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                PrivacyBullet("All filtering on-device — no cloud dependency")
                PrivacyBullet("Zero telemetry — no data leaves your device")
                PrivacyBullet("Open source (GPL-3.0) — every line auditable")
                PrivacyBullet("Minimal permissions — VPN + notification only")
                PrivacyBullet("No account required — no signup, no email")
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.padding(10.dp))
                    Column {
                        Text("WLT-Adblocker", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("v0.1.0 Phase 1 · Go core 1.26.5 · Kotlin/Compose", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                        Text("Built from 35+ adblocker analyses · 300+ features synthesized", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.padding(6.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun LayerToggleRow(name: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PrivacyBullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.padding(6.dp))
        Text(text, fontSize = 12.sp)
    }
}
