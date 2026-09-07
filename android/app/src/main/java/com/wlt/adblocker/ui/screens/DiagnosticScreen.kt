package com.wlt.adblocker.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wlt.adblocker.vpn.DiagnosticState
import kotlinx.coroutines.delay

/**
 * Diagnostic Screen — the #1 feature from the rebuild blueprint.
 *
 * This screen provides runtime observability that was previously missing entirely.
 * It shows:
 *   1. Go Engine Status (loaded? class name? rule count? error?)
 *   2. VPN State (established? TUN interface? RX/TX packets?)
 *   3. Query Log (rolling buffer of last 20 DNS queries)
 *   4. System Warnings (Private DNS? Battery optimization?)
 *   5. Forwarder Stats (active TCP/UDP sessions)
 *
 * Without this screen, "nothing works" was impossible to debug. Now the user
 * can instantly see if the Go engine loaded, if the VPN is up, and what
 * happened to the last DNS query.
 *
 * Design inspired by AdGuard's diagnostics and RethinkDNS's network log.
 *
 * Polish (Task C-ANDROID-UI-POLISH):
 *   - Color-coded status badges: green (OK / primary) vs red (error)
 *   - Query log uses icons (Block / CheckCircle) instead of dots, with
 *     SDK badges for game ads
 *   - Each panel has an icon + status badge in its header for instant scanning
 *   - StatusDot now uses theme primary/error colors instead of hardcoded hex
 *   - Loading + last-updated indicator
 */
@Composable
fun DiagnosticScreen() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Poll DiagnosticState every 1 second for live updates
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            tick++
            delay(1000)
        }
    }

    val engineStatus = DiagnosticState.engineStatus
    val vpnStatus = DiagnosticState.vpnStatus
    val queryLog = DiagnosticState.recentQueries
    val forwarderStats = DiagnosticState.forwarderStats
    val privateDnsMode = remember(tick) { DiagnosticState.getPrivateDnsMode(context) }
    val batteryOptimized = remember(tick) { DiagnosticState.isBatteryOptimized(context) }
    val tunRxPackets = remember(tick) { DiagnosticState.readTunRxPackets() }
    val vpnUptime = remember(tick) { DiagnosticState.getVpnUptime() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header — title, subtitle, and live "polling" indicator.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Diagnostics",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Real-time system visibility — verify the app is actually working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Live "polling" indicator — a small spinner that lets the user
            // know the screen is auto-refreshing.
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // === Panel 1: Go Engine Status ===
        EngineStatusCard(engineStatus)

        // === Panel 2: VPN State ===
        VpnStateCard(vpnStatus, tunRxPackets, vpnUptime)

        // === Panel 3: System Warnings ===
        SystemWarningsCard(privateDnsMode, batteryOptimized, context)

        // === Panel 4: Query Log (recent DNS queries) ===
        QueryLogCard(queryLog)

        // === Panel 5: Forwarder Stats ===
        ForwarderStatsCard(forwarderStats)

        Spacer(modifier = Modifier.height(8.dp))

        // Refresh button (manual re-poll)
        OutlinedButton(
            onClick = { tick++ },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh diagnostics", modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Refresh (tick=$tick)")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// --- Color-coded status badge (used in panel headers) ---

@Composable
private fun StatusBadge(ok: Boolean, label: String) {
    val bg = if (ok) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
    val fg = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(fg, CircleShape),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// --- Panel header (icon + title + status badge) ---

@Composable
private fun PanelHeader(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    ok: Boolean,
    okLabel: String = "OK",
    failLabel: String = "Issue",
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) trailing()
        Spacer(modifier = Modifier.width(8.dp))
        StatusBadge(ok = ok, label = if (ok) okLabel else failLabel)
    }
}

@Composable
private fun EngineStatusCard(status: DiagnosticState.EngineStatus?) {
    val loaded = status?.loaded == true
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (loaded)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PanelHeader(
                icon = Icons.Filled.BugReport,
                iconTint = if (loaded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                title = "Go Engine",
                ok = loaded,
                okLabel = "Loaded",
                failLabel = "Not loaded",
                trailing = {
                    Text(
                        text = status?.engineVersion ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatusRow("Loaded", if (loaded) "YES ✓" else "NO ✗")
            StatusRow("Class", status?.className ?: "(not found)")
            StatusRow("Rules", "${status?.ruleCount ?: 0}")
            if (!status?.error.isNullOrEmpty()) {
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = "Engine error",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Error: ${status?.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun VpnStateCard(status: DiagnosticState.VpnStatus?, tunRxPackets: Long, uptime: String) {
    val established = status?.established == true
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PanelHeader(
                icon = Icons.Filled.Router,
                iconTint = if (established) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                title = "VPN State",
                ok = established,
                okLabel = "Up",
                failLabel = "Down",
                trailing = {
                    if (established) {
                        Text(
                            text = uptime,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatusRow("Established", if (established) "YES ✓" else "NO ✗")
            StatusRow("Interface", status?.tunInterface ?: "(none)")
            StatusRow("Address", status?.tunAddress ?: "(none)")
            StatusRow("Route", status?.route ?: "(none)")
            StatusRow("TUN RX packets", "$tunRxPackets")
            StatusRow("Uptime", uptime)
            if (tunRxPackets == 0L && established) {
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = "No TUN traffic",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "TUN has 0 RX packets — traffic may not be flowing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemWarningsCard(
    privateDnsMode: String,
    batteryOptimized: Boolean,
    context: android.content.Context,
) {
    val privateDnsConflicting = privateDnsMode != "off" && privateDnsMode != "null"
    val hasWarnings = privateDnsConflicting || batteryOptimized
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasWarnings)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PanelHeader(
                icon = if (hasWarnings) Icons.Filled.Warning else Icons.Filled.Check,
                iconTint = if (hasWarnings) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                title = "System Warnings",
                ok = !hasWarnings,
                okLabel = "All clear",
                failLabel = "${if (privateDnsConflicting) 1 else 0}+${if (batteryOptimized) 1 else 0}",
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Private DNS warning
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(loaded = !privateDnsConflicting, size = 8)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Private DNS: $privateDnsMode",
                        style = MaterialTheme.typography.bodySmall)
                    if (privateDnsConflicting) {
                        Text(
                            text = "⚠ Private DNS bypasses VPN — disable for full protection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // Battery optimization warning
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(loaded = !batteryOptimized, size = 8)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Battery Optimization: ${if (batteryOptimized) "ON (may kill VPN)" else "OFF ✓"}",
                        style = MaterialTheme.typography.bodySmall)
                    if (batteryOptimized) {
                        Text(
                            text = "⚠ OEM may kill the VPN service — disable battery optimization",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // Open Private DNS settings button
            if (privateDnsConflicting) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Private DNS Settings")
                }
            }

            // Open Battery Optimization settings button
            if (batteryOptimized) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Battery Optimization Settings")
                }
            }
        }
    }
}

@Composable
private fun QueryLogCard(queries: List<DiagnosticState.QueryEntry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Query Log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = "${queries.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (queries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "No queries yet. Start the VPN and browse to see DNS interception in action.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            } else {
                queries.take(20).forEach { q ->
                    QueryLogRow(q)
                }
            }
        }
    }
}

@Composable
private fun QueryLogRow(q: DiagnosticState.QueryEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Color-coded icon: Block icon (red) for blocked, CheckCircle (green) for allowed.
        // Replaces the old tiny dot with a clearer icon — much easier to scan.
        Icon(
            imageVector = if (q.blocked) Icons.Filled.Block else Icons.Filled.CheckCircle,
            contentDescription = if (q.blocked) "Blocked: ${q.domain}" else "Allowed: ${q.domain}",
            tint = if (q.blocked) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = q.domain,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = q.reason + (q.packageName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // SDK badge — game ad detection.
        // (DiagnosticState.QueryEntry has no sdk field, so we infer it from
        // the reason text — common pattern: "Blocked by Game SDK: AdMob".)
        if (q.reason.contains("SDK", ignoreCase = true)) {
            Icon(
                imageVector = Icons.Filled.SportsEsports,
                contentDescription = "Game ad SDK",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        // BLOCKED / ALLOWED pill
        val pillColor = if (q.blocked) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary
        Surface(
            color = pillColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                text = if (q.blocked) "BLOCKED" else "ALLOWED",
                style = MaterialTheme.typography.labelSmall,
                color = pillColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ForwarderStatsCard(stats: DiagnosticState.ForwarderStats?) {
    val hasSessions = (stats?.activeTcpSessions ?: 0) > 0 || (stats?.activeUdpSessions ?: 0) > 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PanelHeader(
                icon = Icons.Filled.Router,
                iconTint = if (hasSessions) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                title = "Forwarder Stats",
                ok = true, // Forwarder stats are always informational — never an "issue"
                okLabel = "Live",
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatusRow("Active TCP sessions", "${stats?.activeTcpSessions ?: 0}")
            StatusRow("Active UDP sessions", "${stats?.activeUdpSessions ?: 0}")
            StatusRow("Total forwarded", "${stats?.totalForwarded ?: 0} pkts")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatusDot(loaded: Boolean, size: Int = 8) {
    // Uses theme primary (green) for OK and error (red) for failure — no more
    // hardcoded hex colors that don't match the WLT brand palette.
    val color = if (loaded) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color = color, shape = CircleShape),
    )
}
