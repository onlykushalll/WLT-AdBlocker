package com.wlt.adblocker.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.data.PrefsRepository
import com.wlt.adblocker.data.StatsHistory
import com.wlt.adblocker.vpn.BlockStats
import com.wlt.adblocker.vpn.DiagnosticState
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
 *  - Protection status banner (more prominent than the card alone)
 *  - Live stats grid (Queries / Blocked / Allowed / Block Rate) — 2x2 layout
 *  - Block-rate sparkline (Canvas) reading the [StatsHistory] ring buffer
 *  - Last blocked domain live ticker
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
    onOpenDiagnostics: () -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Process-wide BlockStats singleton — instantiated here lazily so the
    // dashboard shows something even before the VPN service is running.
    // The service's own blockStats instance is the same object (singleton
    // companion would be cleaner, but for now we hold a module-level one).
    val blockStats = remember { BlockStats() }
    val statsHistory = remember { StatsHistory() }
    // NOTE: We use DiagnosticState (a process-wide singleton updated by the
    // VPN service) for the "last blocked" ticker — NOT a fresh QueryLog().
    // Creating a new QueryLog() would give us a private empty buffer that
    // never sees VPN-recorded queries. (The same goes for StatsHistory /
    // BlockStats in principle, but those happen to be wired to the same
    // process-wide instances elsewhere — see VPN service onCreate.)

    // Live state — re-snapshotted every 2 seconds.
    var snapshot by remember { mutableStateOf(blockStats.snapshot()) }
    var historyPoints by remember { mutableStateOf(statsHistory.recent(60)) }
    var vpnActive by remember { mutableStateOf(false) }
    var showPauseDialog by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var pauseRemainingMinutes by remember { mutableLongStateOf(0L) }
    var lastBlocked by remember { mutableStateOf<LastBlockedInfo?>(null) }

    // Refresh loop — 2 second cadence. Lower than this and the UI flickers;
    // higher and the dashboard feels dead.
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = blockStats.snapshot()
            historyPoints = statsHistory.recent(60)
            // Check VPN active state by attempting to bind to the service —
            // for now we use a simple heuristic: if blocked or allowed > 0,
            // the VPN must be running. Also check paused state.
            val pauseUntil = PrefsRepository.getCachedPauseUntil()
            val paused = pauseUntil > System.currentTimeMillis()
            isPaused = paused
            if (paused) {
                // While paused, the VPN service is still running but not blocking.
                vpnActive = true
                pauseRemainingMinutes =
                    (pauseUntil - System.currentTimeMillis()) / 60_000L
            } else {
                vpnActive = snapshot.totalBlocked > 0 || snapshot.totalAllowed > 0
            }
            // Last blocked domain — read from the SHARED DiagnosticState
            // (process-wide singleton updated by the VPN service). Using a
            // private QueryLog() instance would never see VPN queries.
            lastBlocked = DiagnosticState.recentQueries
                .firstOrNull { it.blocked }
                ?.let {
                    LastBlockedInfo(
                        domain = it.domain,
                        timestamp = it.timestamp,
                        sdk = null, // DiagnosticState.QueryEntry has no sdk field
                    )
                }
            delay(2_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // === Prominent protection status banner ===
        // Always visible — clearly tells the user whether they're protected.
        item {
            ProtectionStatusBanner(
                vpnActive = vpnActive,
                isPaused = isPaused,
                onToggleVpn = { turnOn ->
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
                },
                onPauseClicked = { showPauseDialog = true },
            )
        }

        if (isPaused) {
            item {
                PauseStatusCard(
                    minutesRemaining = pauseRemainingMinutes.coerceAtLeast(0L).toInt(),
                    onResume = { WltVpnService.resumeVPN(context) },
                )
            }
        }

        // === Live "last blocked" ticker ===
        // Shows the most recently blocked domain — gives instant feedback that
        // WLT is actively filtering. Animates in/out as new blocks arrive.
        item {
            LastBlockedTicker(entry = lastBlocked)
        }

        item { StatsGrid(snapshot = snapshot) }

        item { BlockRateChart(points = historyPoints) }

        item { SmartCascadeCard() }

        // Diagnostics quick-access card — the #1 feature for debugging "nothing works"
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = "Diagnostics",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Diagnostics",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Check if the Go engine loaded, VPN is up, and see live queries",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onOpenDiagnostics) {
                        Text("Open")
                    }
                }
            }
        }

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

// --- Prominent protection status banner ---

@Composable
private fun ProtectionStatusBanner(
    vpnActive: Boolean,
    isPaused: Boolean,
    onToggleVpn: (Boolean) -> Unit,
    onPauseClicked: () -> Unit,
) {
    // Pulse animation — only when VPN is active and not paused.
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    // The banner uses different surface colors based on state:
    //  - Active & not paused → primaryContainer (green) — "you're protected"
    //  - Paused               → secondaryContainer (amber) — "temporarily off"
    //  - Off                  → surfaceVariant — neutral "off" state
    val containerColor = when {
        vpnActive && !isPaused -> MaterialTheme.colorScheme.primaryContainer
        isPaused -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainerColor = when {
        vpnActive && !isPaused -> MaterialTheme.colorScheme.onPrimaryContainer
        isPaused -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconTint = when {
        vpnActive && !isPaused -> MaterialTheme.colorScheme.primary
        isPaused -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Animated shield — pulses when protecting, static otherwise.
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Soft glow ring behind the shield when active.
                if (vpnActive && !isPaused) {
                    Box(
                        modifier = Modifier
                            .size((96 * pulseScale).dp)
                            .clip(CircleShape)
                            .background(iconTint.copy(alpha = 0.15f)),
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = if (vpnActive && !isPaused)
                        "Protection active" else "Protection off",
                    tint = iconTint,
                    modifier = Modifier.size(
                        (72 * if (vpnActive && !isPaused) pulseScale else 1f).dp,
                    ),
                )
            }
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = when {
                    vpnActive && !isPaused -> "Protection Active"
                    isPaused -> "Protection Paused"
                    else -> "Protection Off"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = onContainerColor,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = when {
                    vpnActive && !isPaused -> "Your DNS queries are being filtered locally."
                    isPaused -> "All queries are passing through unfiltered."
                    else -> "Tap the switch below to start protecting your device."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = onContainerColor.copy(alpha = 0.85f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                    color = onContainerColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(
                    checked = vpnActive,
                    onCheckedChange = onToggleVpn,
                )
            }
            // Pause button — only visible when VPN is active and not paused.
            AnimatedVisibility(
                visible = vpnActive && !isPaused,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically(),
            ) {
                Column {
                    Spacer(modifier = Modifier.size(8.dp))
                    OutlinedButton(
                        onClick = onPauseClicked,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = "Pause protection",
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text("Pause Protection")
                    }
                }
            }
        }
    }
}

// --- Last blocked domain live ticker ---

/** Lightweight holder for the "last blocked" ticker — extracted so we don't
 *  couple the dashboard to a specific QueryLog / DiagnosticState type. */
private data class LastBlockedInfo(
    val domain: String,
    val timestamp: Long,
    val sdk: String? = null,
)

@Composable
private fun LastBlockedTicker(entry: LastBlockedInfo?) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pulsing red dot — "live" indicator.
            val infiniteTransition = rememberInfiniteTransition(label = "live")
            val liveAlpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "liveAlpha",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.error.copy(alpha = liveAlpha),
                        shape = CircleShape,
                    ),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Last blocked",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(12.dp))
            // Domain name (or placeholder)
            if (entry != null) {
                Text(
                    text = entry.domain,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.sdk != null) {
                    Icon(
                        imageVector = Icons.Filled.SportsEsports,
                        contentDescription = "Game ad SDK: ${entry.sdk}",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = timeFmt.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Nothing blocked yet — start the VPN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --- Live stats grid (2x2 layout for better phone readability) ---

@Composable
private fun StatsGrid(snapshot: BlockStats.StatsSnapshot) {
    val rate = (snapshot.blockRate * 100).toInt().coerceIn(0, 100)
    val totalQueries = snapshot.totalBlocked + snapshot.totalAllowed

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Total Queries",
                value = formatCount(totalQueries),
                icon = Icons.Filled.Public,
                tint = MaterialTheme.colorScheme.primary,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Blocked",
                value = formatCount(snapshot.totalBlocked),
                icon = Icons.Filled.Block,
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Allowed",
                value = formatCount(snapshot.totalAllowed),
                icon = Icons.Filled.CheckCircle,
                tint = MaterialTheme.colorScheme.primary,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Block Rate",
                value = "$rate%",
                icon = Icons.Filled.Security,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
        // Block rate progress bar — gives a quick visual sense of "how much
        // of my traffic is ads/trackers" without needing to read the number.
        Spacer(modifier = Modifier.size(4.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Block rate",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(4.dp))
            LinearProgressIndicator(
                progress = { snapshot.blockRate },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** Formats a long count with thousand separators for readability. */
private fun formatCount(v: Long): String {
    return when {
        v < 1_000 -> v.toString()
        v < 1_000_000 -> {
            val thousands = v / 1_000
            val remainder = (v % 1_000) / 100
            if (remainder == 0L) "${thousands}k" else "${thousands}.${remainder}k"
        }
        else -> {
            val millions = v / 1_000_000
            val remainder = (v % 1_000_000) / 100_000
            if (remainder == 0L) "${millions}M" else "${millions}.${remainder}M"
        }
    }
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
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null, // Decorative — the label conveys meaning
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.size(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Block-rate sparkline (Canvas) ---

@Composable
private fun BlockRateChart(points: List<StatsHistory.Point>) {
    // Capture theme colors OUTSIDE the Canvas — Canvas's DrawScope has no
    // access to MaterialTheme. Previously hardcoded to #0F7B6C.
    val lineColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
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
                    text = "Block rate (last ${points.size} min)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (points.isNotEmpty()) {
                    val avg = points.sumOf { it.blocked }.toFloat() /
                        maxOf(points.sumOf { it.total() }, 1)
                    Text(
                        text = "avg ${(avg * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            if (points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "No data yet — start the VPN to begin tracking.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

                // Light track behind the fill so empty regions read as "0%"
                // rather than just blank.
                drawRect(color = trackColor.copy(alpha = 0.25f))

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
                        contentDescription = "${layer.name} layer",
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
                    CascadeStatusBadge(active = layer.active)
                }
            }
        }
    }
}

@Composable
private fun CascadeStatusBadge(active: Boolean) {
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(fg, CircleShape),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (active) "Active" else "Off",
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.SemiBold,
            )
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Nothing blocked yet. Start the VPN and browse " +
                            "to see what WLT is catching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
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
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.sdk != null) {
                            Icon(
                                imageVector = Icons.Filled.SportsEsports,
                                contentDescription = "Game ad SDK: ${item.sdk}",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = formatCount(item.count),
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
