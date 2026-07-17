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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ForensicsScreen() {
    // Phase 1: static explanatory content. Phase 2 will read live from the Go forensics recorder.
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Ad Forensics", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "When an ad slips past, WLT explains exactly which layer missed it and gives a one-tap fix.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        item {
            ForensicTraceCard(
                domain = "ads.example-game.com",
                sdk = "AdMob",
                layers = listOf(
                    Triple("DNS", "MISSED", "Hardcoded IP 34.120.x.x used, no DNS query"),
                    Triple("SNI", "WOULD BLOCK", "SNI matched gamesdk:admob"),
                    Triple("HTTPS", "N/A", "Cert-pinned app")
                ),
                fixAction = "Enable SNI inspection for this app",
                fixColor = MaterialTheme.colorScheme.primary
            )
        }
        item {
            ForensicTraceCard(
                domain = "tracking.cnn.io",
                sdk = "Unknown",
                layers = listOf(
                    Triple("DNS", "ALLOWED", "Not in blocklist"),
                    Triple("SNI", "WOULD BLOCK", "CNAME cloak to ea-cdn.com detected"),
                    Triple("HTTPS", "N/A", "Layer disabled")
                ),
                fixAction = "Enable SNI layer + CNAME cloaking list",
                fixColor = MaterialTheme.colorScheme.primary
            )
        }
        item {
            ForensicTraceCard(
                domain = "youtube.com",
                sdk = "First-party",
                layers = listOf(
                    Triple("DNS", "ALLOWED", "Passthrough (critical infra)"),
                    Triple("SNI", "N/A", "Layer disabled"),
                    Triple("HTTPS", "WOULD BLOCK", "m3u-prune scriptlet available"),
                    Triple("Scriptlet", "WOULD BLOCK", "YouTube ad-segment stripper ready")
                ),
                fixAction = "Enable HTTPS filtering for browsers (Phase 3)",
                fixColor = MaterialTheme.colorScheme.secondary
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.padding(10.dp))
                    Column {
                        Text("The WLT Difference", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "No adblocker explains its own failures. Users blame the blocker; WLT tells you exactly why an ad got through and how to fix it — with a one-tap action.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.padding(8.dp))
                        Text("Honest Limits", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LimitRow("YouTube app ads", "Same-domain SSAI — use ReVanced")
                    LimitRow("Instagram sponsored", "First-party, same domain — flagged not blocked")
                    LimitRow("Cert-pinned + hardcoded IP", "Partial — SNI + IP blocklist only")
                    LimitRow("Offline cached game ads", "Cannot block — content is local")
                }
            }
        }
    }
}

@Composable
private fun ForensicTraceCard(
    domain: String,
    sdk: String,
    layers: List<Triple<String, String, String>>,
    fixAction: String,
    fixColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.padding(6.dp))
                Text(domain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(sdk, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(10.dp))
            layers.forEach { (layer, status, reason) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusColor = when {
                        status == "BLOCKED" -> MaterialTheme.colorScheme.error
                        status == "MISSED" -> MaterialTheme.colorScheme.error
                        status.startsWith("WOULD") -> MaterialTheme.colorScheme.secondary
                        status == "ALLOWED" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Text(layer, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(72.dp))
                    Text(status, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
                    Text(reason, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = fixColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.padding(4.dp))
                Text(fixAction, fontSize = 12.sp, color = fixColor, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun LimitRow(scenario: String, limit: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
        Spacer(Modifier.padding(6.dp))
        Text(scenario, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(limit, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
