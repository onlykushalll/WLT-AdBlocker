package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.data.QueryLog
import com.wlt.adblocker.vpn.BlockStats
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live forensics screen.
 *
 * WLT's "explain your failures" philosophy: instead of pretending we block
 * 100% of ads, we show the user exactly what we blocked AND what we let
 * through. The screen has three sections:
 *
 *  1. Live stats summary (blocked / allowed / block rate)
 *  2. Recently Blocked — last 15 blocked queries, with reason + SDK badge
 *  3. Recently Allowed — last 5 allowed queries, for transparency
 *
 * Plus two educational cards:
 *  - The "explain your failures" philosophy
 *  - Honest limits (YouTube app, Instagram, cert-pinned SDKs, offline)
 *
 * Auto-refreshes every 2 seconds via a [LaunchedEffect].
 *
 * CRITICAL (Task 39 fix): NO duplicate `modifier` argument in any composable.
 * Each composable has at most one `modifier` parameter, and it's combined
 * into a single chain (e.g., `Modifier.fillMaxWidth().padding(16.dp)`).
 */
@Composable
fun ForensicsScreen() {
    val queryLog = remember { QueryLog() }
    val blockStats = remember { BlockStats() }

    var snapshot by remember { mutableStateOf(blockStats.snapshot()) }
    var blockedEntries by remember { mutableStateOf(queryLog.recentBlocked(15)) }
    var allowedEntries by remember { mutableStateOf(queryLog.recentAllowed(5)) }

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = blockStats.snapshot()
            blockedEntries = queryLog.recentBlocked(15)
            allowedEntries = queryLog.recentAllowed(5)
            delay(2_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatsSummaryCard(
                blocked = snapshot.totalBlocked,
                allowed = snapshot.totalAllowed,
                rate = snapshot.blockRate,
            )
        }
        item { RecentlyBlockedCard(entries = blockedEntries) }
        item { RecentlyAllowedCard(entries = allowedEntries) }
        item { PhilosophyCard() }
        item { HonestLimitsCard() }
    }
}

@Composable
private fun StatsSummaryCard(
    blocked: Long,
    allowed: Long,
    rate: Float,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            // Task 39 fix: single Modifier chain, no duplicate `modifier` arg.
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BigStat("Blocked", blocked.toString(), MaterialTheme.colorScheme.onPrimaryContainer)
            BigStat("Allowed", allowed.toString(), MaterialTheme.colorScheme.onPrimaryContainer)
            BigStat(
                label = "Block rate",
                value = "${(rate * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun RecentlyBlockedCard(entries: List<QueryLog.Entry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Recently Blocked",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(8.dp))
            if (entries.isEmpty()) {
                Text(
                    text = "Nothing blocked yet. Start the VPN to begin tracking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Bounded-height scrollable list (~max-h-96) — newest first.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 384.dp),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(entries.reversed()) { entry ->
                            ForensicEntryRow(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentlyAllowedCard(entries: List<QueryLog.Entry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Recently Allowed (transparency)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(8.dp))
            if (entries.isEmpty()) {
                Text(
                    text = "No allowed queries logged yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (entry in entries.reversed()) {
                    ForensicEntryRow(entry)
                }
            }
        }
    }
}

@Composable
private fun ForensicEntryRow(entry: QueryLog.Entry) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.blocked) Icons.Filled.Block
            else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (entry.blocked) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.domain,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = entry.reason +
                    (entry.sdk?.let { " · $it" } ?: "") +
                    (entry.packageName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.sdk != null) {
            Icon(
                imageVector = Icons.Filled.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = timeFmt.format(Date(entry.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhilosophyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Explain your failures",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = "Most ad blockers brag about block rates and hide " +
                    "what they let through. WLT does the opposite: every " +
                    "allowed query is shown here, with the reason it " +
                    "wasn't blocked. If something slipped past us, you'll " +
                    "see it — and you can add it as a custom rule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun HonestLimitsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Honest limits",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(4.dp))
            val limits = listOf(
                "YouTube app SSAI ads — cert-pinned, can't be MITM'd without root",
                "Instagram feed ads — same as YouTube, cert-pinned",
                "Apps with cert pinning — WLT can't intercept their TLS",
                "Offline / cached ads — already on disk, no DNS query to block",
                "Hardcoded IP addresses — no DNS lookup to intercept",
            )
            for (limit in limits) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.size(8.dp).padding(top = 6.dp),
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = limit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
