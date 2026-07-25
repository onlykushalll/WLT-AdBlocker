package com.wlt.adblocker.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.data.SettingsExportImport
import com.wlt.adblocker.vpn.BlockStats

/**
 * Settings screen.
 *
 * Section 1: Smart Cascade layer toggles (DNS / SNI / HTTPS / Scriptlet)
 * Section 2: Block response segmented buttons (NXDOMAIN / 0.0.0.0 / REFUSED)
 * Section 3: Upstream DNS selector (Cloudflare / Google / Quad9 / AdGuard)
 * Section 4: Auto-update blocklists toggle (24h WorkManager)
 * Section 5: Theme selector (System / Light / Dark)
 * Section 6: Privacy (no telemetry notice, export/import buttons)
 * Section 7: About (version, open-source link)
 *
 * Toggles write to a (TODO) WltDataStore; for now they're local state only.
 * The actual VPN service reads these on startup. Writing them requires the
 * WltDataStore class to exist (Task 40-c's data layer doesn't include it
 * yet — it has PrefsRepository but only for first-launch + pause). When the
 * DataStore is wired, the toggles will write through to it.
 */
@Composable
fun SettingsScreen(
    onOpenDnsLatency: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Local state — would be backed by DataStore in a fuller implementation.
    var dnsLayer by remember { mutableStateOf(true) }
    var sniLayer by remember { mutableStateOf(false) }
    var httpsLayer by remember { mutableStateOf(false) }
    var scriptletLayer by remember { mutableStateOf(false) }
    var blockResponse by remember { mutableStateOf("NXDOMAIN") }
    var upstreamDns by remember { mutableStateOf("cloudflare") }
    var autoUpdate by remember { mutableStateOf(true) }
    var theme by remember { mutableStateOf("system") }

    val exportImport = remember { SettingsExportImport(context) }
    val blockStats = remember { BlockStats() }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val snap = blockStats.snapshot()
            val topBlocked = snap.perDomainTop.take(20).map { it.domain to it.count.toInt() }
            exportImport.exportToUri(
                uri,
                SettingsExportImport.ExportStats(
                    totalBlocked = snap.totalBlocked,
                    totalAllowed = snap.totalAllowed,
                    topBlocked = topBlocked,
                ),
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) exportImport.importFromUri(uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // --- Section 1: Smart Cascade ---
        SectionCard("Smart Cascade Layers") {
            LayerToggle("DNS", "Block by domain at resolver level", Icons.Filled.Dns, dnsLayer) { dnsLayer = it }
            LayerToggle("SNI", "Inspect TLS ClientHello SNI", Icons.Filled.Public, sniLayer) { sniLayer = it }
            LayerToggle("HTTPS", "MITM trusted connections (CA-gated)", Icons.Filled.Http, httpsLayer) { httpsLayer = it }
            LayerToggle("Scriptlet", "Inject anti-adblock JS", Icons.Filled.SportsEsports, scriptletLayer) { scriptletLayer = it }
        }

        // --- Section 2: Block response ---
        SectionCard("Block Response") {
            Text(
                text = "What to return for blocked domains. NXDOMAIN is safest (domain doesn't exist); 0.0.0.0 is fastest (immediate connection failure); REFUSED is most explicit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedButton("NXDOMAIN", blockResponse == "NXDOMAIN") { blockResponse = "NXDOMAIN" }
                SegmentedButton("0.0.0.0", blockResponse == "0.0.0.0") { blockResponse = "0.0.0.0" }
                SegmentedButton("REFUSED", blockResponse == "REFUSED") { blockResponse = "REFUSED" }
            }
        }

        // --- Section 3: Upstream DNS ---
        SectionCard("Upstream DNS") {
            Text(
                text = "Used for forwarding allowed queries. DoH-first for privacy (RFC 8484), with UDP fallback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in listOf("cloudflare", "google", "quad9", "adguard")) {
                    SegmentedButton(
                        label = name.replaceFirstChar { it.uppercase() },
                        selected = upstreamDns == name,
                    ) { upstreamDns = name }
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            OutlinedButton(onClick = onOpenDnsLatency) {
                Text("Test latency →")
            }
        }

        // --- Section 4: Auto-update ---
        SectionCard("Auto-Update") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-update blocklists",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Downloads fresh OISD/HaGeZi lists every 24h via WorkManager.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoUpdate, onCheckedChange = { autoUpdate = it })
            }
        }

        // --- Section 5: Theme ---
        SectionCard("Theme") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedButton("System", theme == "system") { theme = "system" }
                SegmentedButton("Light", theme == "light") { theme = "light" }
                SegmentedButton("Dark", theme == "dark") { theme = "dark" }
            }
        }

        // --- Section 6: Privacy ---
        SectionCard("Privacy") {
            Text(
                text = "WLT collects no analytics, no crash reports, no telemetry. " +
                    "Your settings stay on your device. Export them to a JSON " +
                    "file if you want a backup or want to transfer to another device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    exportLauncher.launch("wlt-settings.json")
                }) { Text("Export") }
                OutlinedButton(onClick = {
                    importLauncher.launch(arrayOf("application/json"))
                }) { Text("Import") }
            }
        }

        // --- Section 7: About ---
        SectionCard("About") {
            Text(
                text = "WLT-Adblocker v1.0.0",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Open-source local VPN ad blocker for Android.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "No root. No cloud. No telemetry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
private fun LayerToggle(
    name: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SegmentedButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
