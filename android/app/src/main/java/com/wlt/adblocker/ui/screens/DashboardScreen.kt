package com.wlt.adblocker.ui.screens

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.data.PrefsRepository
import com.wlt.adblocker.data.StatsHistory
import com.wlt.adblocker.vpn.BlockStats
import com.wlt.adblocker.vpn.WltVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The dashboard.
 *
 * This screen is the user's primary "is WLT working?" surface. It shows:
 *  - Animated protection shield card (with pulse effect when VPN active)
 *  - Live stats grid (Queries / Blocked / Allowed / Block Rate)
 *  - Block-rate sparkline (Canvas) reading the [StatsHistory] ring buffer
 *  - Smart Cascade layer status card (DNS / SNI / HTTPS / Scriptlet)
 *  - Top Blocked Domains list
 *  - VPN Switch + Pause button + PauseStatusCard when paused
 *
 * Live data: every 2 seconds we re-snapshot [BlockStats] and [StatsHistory]
 * via a [LaunchedEffect]. The VPN service records stats every 60 seconds
 * (see WltVpnService.statsRecorder), so the chart updates roughly every
 * minute in practice.
 *
 * Process-wide singletons: [BlockStats] is owned by the VPN service. If the
 * service isn't running, the dashboard shows zeros — there's nothing to
 * display because no queries are being intercepted.
 */
@Composable
fun DashboardScreen(
    onOpenForensics: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Process-wide BlockStats singleton — instantiated here lazily so the
    // dashboard shows something even before the VPN service is running.
    // The service's own blockStats instance is the same object (singleton
    // companion would be cleaner, but for now we hold a module-level one).
    val blockStats = remember { BlockStats() }
    val statsHistory = remember { StatsHistory() }

    // Live state — re-snapshotted every 2 seconds.
    var snapshot by remember { mutableStateOf(blockStats.snapshot()) }
    var historyPoints by remember { mutableStateOf(statsHistory.recent(60)) }
    var vpnActive by remember { mutableStateOf(false) }
    var showPauseDialog by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var pauseRemainingMinutes by remember { mutableLongStateOf(0L) }
    var lastTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Refresh loop — 2 second cadence. Lower than this and the UI flickers;
    // higher and the dashboard feels dead.
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = blockStats.snapshot()
            historyPoints = statsHistory.recent(60)
            // Check VPN active state by attempting to bind to the service —
            // for now we use a simple heuristic: if blocked or allowed > 0,
            // the VPN must be running.
            vpnActive = snapshot.totalBlocked > 0 || snapshot.totalAllowed > 0
            // Refresh pause state from cached prefs.
            val pauseUntil = PrefsRepository.getCachedPauseUntil()
            isPaused = pauseUntil > System.currentTimeMillis()
            if (isPaused) {
                pauseRemainingMinutes =
                    (pauseUntil - System.currentTimeMillis()) / 60_000L
            }
            lastTick = System.currentTimeMillis()
            delay(2_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ProtectionCard(vpnActive = vpnActive, onToggleVpn = { turnOn ->
            coroutineScope.launch {
                if (turnOn) {
                    // Hand off to MainActivity for the permission flow.
                    val intent = Intent(context, com.wlt.adblocker.MainActivity::class.java).apply {
                        putExtra(com.wlt.adblocker.MainActivity.EXTRA_AUTO_START_VPN, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } else {
                    WltVpnService.stopVPN(context)
                }
            }
        }, onPauseClicked = { showPauseDialog = true }) }

        if (isPaused) {
            item {
                PauseStatusCard(
                    minutesRemaining = pauseRemainingMinutes.coerceAtLeast(0L).toInt(),
                    onResume = { WltVpnService.resumeVPN(context) },
                )
            }
        }

        item { StatsGrid(snapshot = snapshot) }

        item { BlockRateChart(points = historyPoints) }

        item { SmartCascadeCard() }

        item {
            TopBlockedCard(
                topBlocked = snapshot.perDomainTop.take(10),
                onOpenForensics = onOpenForensics,
            )
        }

        item { InfoCard() }
    }

    if (showPauseDialog) {
        PauseProtectionDialog(
            onConfirm = { minutes ->
                showPauseDialog = false
                WltVpnService.pauseVPN(context, minutes)
            },
            onDismiss = { showPauseDialog = false },
        )
    }
}

// --- Protection card ---

@Composable
private fun ProtectionCard(
    vpnActive: Boolean,
    onToggleVpn: (Boolean) -> Unit,
    onPauseClicked: () -> Unit,
) {
    // Pulse animation — only when VPN is active.
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (vpnActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = if (vpnActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(
                    (96 * if (vpnActive) pulseScale else 1f).dp,
                ),
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = if (vpnActive) "Protection Active" else "Protection Off",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (vpnActive) "VPN running" else "VPN stopped",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = vpnActive,
                    onCheckedChange = onToggleVpn,
                )
            }
            if (vpnActive) {
                Spacer(modifier = Modifier.size(8.dp))
                OutlinedButton(
                    onClick = onPauseClicked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text("Pause")
                }
            }
        }
    }
}

// --- Live stats grid ---

@Composable
private fun StatsGrid(snapshot: BlockStats.StatsSnapshot) {
    val rate = (snapshot.blockRate * 100).toInt().coerceIn(0, 100)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatTile(
            modifier = Modifier.weight(1f),
            label = "Queries",
            value = "${snapshot.totalBlocked + snapshot.totalAllowed}",
            icon = Icons.Filled.Public,
            tint = MaterialTheme.colorScheme.primary,
        )
        StatTile(
            modifier = Modifier.weight(1f),
            label = "Blocked",
            value = "${snapshot.totalBlocked}",
            icon = Icons.Filled.Block,
            tint = MaterialTheme.colorScheme.error,
        )
        StatTile(
            modifier = Modifier.weight(1f),
            label = "Allowed",
            value = "${snapshot.totalAllowed}",
            icon = Icons.Filled.CheckCircle,
            tint = MaterialTheme.colorScheme.primary,
        )
        StatTile(
            modifier = Modifier.weight(1f),
            label = "Block %",
            value = "$rate%",
            icon = Icons.Filled.Security,
            tint = MaterialTheme.colorScheme.secondary,
        )
    }
    Spacer(modifier = Modifier.size(4.dp))
    LinearProgressIndicator(
        progress = { snapshot.blockRate },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun StatTile(
    modifier: Modifier,
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Block-rate sparkline (Canvas) ---

@Composable
private fun BlockRateChart(points: List<StatsHistory.Point>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Block rate (last ${points.size} min)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(8.dp))
            if (points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No data yet — start the VPN to begin tracking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            Canvas(
                modifier = Modifier.fillMaxWidth().height(120.dp),
            ) {
                val w = size.width
                val h = size.height
                val n = points.size
                if (n < 2) return@Canvas
                val stepX = w / (n - 1).coerceAtLeast(1)
                val lineColor = Color(0xFF0F7B6C)
                val fillColor = lineColor.copy(alpha = 0.25f)

                val path = Path()
                val fillPath = Path()
                fillPath.moveTo(0f, h)
                for ((i, p) in points.withIndex()) {
                    val x = i * stepX
                    val y = h - (p.blockRate() * h)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                fillPath.lineTo(w, h)
                fillPath.close()

                drawPath(fillPath, brush = Brush.verticalGradient(
                    colors = listOf(fillColor, Color.Transparent),
                ))
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 3f),
                )
                // Dots at each point
                for ((i, p) in points.withIndex()) {
                    val x = i * stepX
                    val y = h - (p.blockRate() * h)
                    drawCircle(
                        color = lineColor,
                        radius = 3f,
                        center = Offset(x, y),
                    )
                }
            }
        }
    }
}

// --- Smart Cascade layer status card ---

@Composable
private fun SmartCascadeCard() {
    val layers = listOf(
        CascadeLayer("DNS", "Block by domain at resolver level", Icons.Filled.Dns, true),
        CascadeLayer("SNI", "Inspect TLS ClientHello SNI", Icons.Filled.Public, false),
        CascadeLayer("HTTPS", "MITM trusted connections (CA-gated)", Icons.Filled.Http, false),
        CascadeLayer("Scriptlet", "Inject anti-adblock JS", Icons.Filled.SportsEsports, false),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Smart Cascade",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(8.dp))
            for (layer in layers) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = layer.icon,
                        contentDescription = null,
                        tint = if (layer.active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = layer.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = layer.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = if (layer.active) "Active" else "Off",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (layer.active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

private data class CascadeLayer(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val active: Boolean,
)

// --- Top blocked domains ---

@Composable
private fun TopBlockedCard(
    topBlocked: List<BlockStats.DomainCount>,
    onOpenForensics: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Top Blocked Domains",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(onClick = onOpenForensics) {
                    Text("Forensics")
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            if (topBlocked.isEmpty()) {
                Text(
                    text = "Nothing blocked yet. Start the VPN and browse " +
                        "to see what WLT is catching.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (item in topBlocked) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.domain,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        if (item.sdk != null) {
                            Icon(
                                imageVector = Icons.Filled.SportsEsports,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp).padding(end = 4.dp),
                            )
                        }
                        Text(
                            text = "${item.count}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

// --- Info card (first-time user help) ---

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "How WLT works",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = "WLT runs as a local VPN. Your traffic never leaves " +
                    "the device — only DNS queries are inspected, and only " +
                    "to decide whether to sinkhole them. No upstream " +
                    "server sees your browsing history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
