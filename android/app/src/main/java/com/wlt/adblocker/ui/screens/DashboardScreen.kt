package com.wlt.adblocker.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wlt.adblocker.data.StatsHistory
import com.wlt.adblocker.vpn.BlockStats
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(vpnEnabled: Boolean, onToggleVpn: (Boolean) -> Unit) {
    var queries by remember { mutableLongStateOf(0L) }
    var blocked by remember { mutableLongStateOf(0L) }
    var allowed by remember { mutableLongStateOf(0L) }
    var topDomains by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var history by remember { mutableStateOf<List<StatsHistory.Point>>(emptyList()) }

    LaunchedEffect(vpnEnabled) {
        while (vpnEnabled) {
            queries = BlockStats.totalQueries()
            blocked = BlockStats.totalBlocked()
            allowed = BlockStats.totalAllowed()
            topDomains = BlockStats.topBlockedDomains(10)
            history = StatsHistory.recent(30)
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { ProtectionCard(vpnEnabled = vpnEnabled, onToggle = onToggleVpn) }
        item { StatsGrid(queries = queries, blocked = blocked, allowed = allowed) }
        if (vpnEnabled && history.size > 1) {
            item { BlockRateChart(history) }
        }
        if (vpnEnabled) {
            item { LayerStatusCard() }
        }
        if (vpnEnabled && topDomains.isNotEmpty()) {
            item { TopBlockedCard(topDomains) }
        }
        if (!vpnEnabled) {
            item {
                InfoCard(Icons.Filled.Security, "WLT Adblocker",
                    "Tap the switch to start system-wide ad blocking. WLT uses a VPN to filter DNS — no root required, 100% on-device, zero telemetry.")
            }
        }
        item {
            InfoCard(Icons.Filled.Bolt, "Why WLT?",
                "Combines the best of uBlock, AdGuard, Rethink, HostShield, and BlockAds — plus Game Ad Intelligence and Ad Forensics no other blocker has. Free, open source.")
        }
    }
}

@Composable
private fun ProtectionCard(vpnEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "pulseScale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (vpnEnabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(120.dp).clip(CircleShape).background(
                    if (vpnEnabled) Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = pulse), MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) else Brush.radialGradient(
                        colors = listOf(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), MaterialTheme.colorScheme.surfaceVariant)
                    )
                ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Shield, contentDescription = if (vpnEnabled) "Protected" else "Unprotected",
                    modifier = Modifier.size(60.dp),
                    tint = if (vpnEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(16.dp))
            Text(if (vpnEnabled) "PROTECTED" else "NOT PROTECTED", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = if (vpnEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(if (vpnEnabled) "DNS filtering active" else "Tap to enable system-wide ad blocking",
                fontSize = 13.sp,
                color = if (vpnEnabled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
            Switch(checked = vpnEnabled, onCheckedChange = onToggle, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun StatsGrid(queries: Long, blocked: Long, allowed: Long) {
    val blockRate = if (queries == 0L) 0f else blocked.toFloat() / queries
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(Modifier.weight(1f), Icons.Filled.Public, "Queries", queries.toString(), MaterialTheme.colorScheme.tertiary)
            StatCard(Modifier.weight(1f), Icons.Filled.Block, "Blocked", blocked.toString(), MaterialTheme.colorScheme.error)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(Modifier.weight(1f), Icons.Filled.CheckCircle, "Allowed", allowed.toString(), MaterialTheme.colorScheme.primary)
            StatCard(Modifier.weight(1f), Icons.Filled.Speed, "Block Rate", "%.0f%%".format(blockRate * 100), MaterialTheme.colorScheme.secondary)
        }
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Block Rate", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { blockRate },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, tint: Color) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BlockRateChart(history: List<StatsHistory.Point>) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val chartColor = if (isDark) Color(0xFF00C896) else Color(0xFF00A87E)
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Block Rate Over Time", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Last ${history.size} minutes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
                if (history.size < 2) return@Canvas
                val stepX = size.width / (history.size - 1).coerceAtLeast(1)
                val fillColor = chartColor.copy(alpha = 0.15f)

                // Build path for block rate area
                val path = Path()
                val fillPath = Path()
                history.forEachIndexed { i, point ->
                    val x = i * stepX
                    val rate = point.blockRate()
                    val y = size.height - (rate * size.height)
                    if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, size.height); fillPath.lineTo(x, y) }
                    else { path.lineTo(x, y); fillPath.lineTo(x, y) }
                }
                fillPath.lineTo(size.width, size.height)
                fillPath.close()

                drawPath(fillPath, color = fillColor)
                drawPath(path, color = chartColor, style = Stroke(width = 3f, cap = StrokeCap.Round))

                // Draw dots at each point
                history.forEachIndexed { i, point ->
                    val x = i * stepX
                    val y = size.height - (point.blockRate() * size.height)
                    drawCircle(color = chartColor, radius = 3f, center = Offset(x, y))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${history.firstOrNull()?.blocked ?: 0} blocked", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                Text("${history.lastOrNull()?.blocked ?: 0} blocked", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun LayerStatusCard() {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Smart Cascade Layers", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            LayerRow("DNS", "Active — blocking at DNS level", true, Icons.Filled.Dns)
            LayerRow("SNI", "Phase 2 — TLS inspection", false, Icons.Filled.Lock)
            LayerRow("HTTPS", "Phase 3 — URL filtering", false, Icons.Filled.Lock)
            LayerRow("Scriptlet", "Phase 3 — JS injection", false, Icons.Filled.Lock)
        }
    }
}

@Composable
private fun LayerRow(name: String, desc: String, active: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(70.dp))
        Text(desc, fontSize = 11.sp, color = if (active) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
        if (active) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun TopBlockedCard(domains: List<Pair<String, Long>>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Top Blocked Domains", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            domains.forEachIndexed { i, (domain, count) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(24.dp))
                    Text(domain, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(count.toString(), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
            }
        }
    }
}
