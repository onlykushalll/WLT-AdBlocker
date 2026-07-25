package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.data.AppNetworkStats
import com.wlt.adblocker.data.BlockCategory
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAnalyticsScreen() {
    // In production, this would be injected from WltVpnService
    val aggregate = remember { AppNetworkStats.AggregateStats(0, 0, 0, 0, 0f) }
    val topApps = remember { listOf<AppNetworkStats.AppStats>() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Summary card
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("App Analytics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem("Apps", aggregate.totalApps.toString())
                    StatItem("Queries", NumberFormat.getInstance().format(aggregate.totalQueries))
                    StatItem("Blocked", NumberFormat.getInstance().format(aggregate.totalBlocked))
                    StatItem("Rate", "${(aggregate.blockRate * 100).toInt()}%")
                }
            }
        }

        if (topApps.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Analytics, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(16.dp))
                    Text("No app activity yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Start the VPN and browse to see per-app stats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // App list
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(topApps) { appStats ->
                    AppStatsCard(appStats)
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppStatsCard(stats: AppNetworkStats.AppStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stats.packageName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(
                    "${(stats.blocked.get().toFloat() / maxOf(stats.queries.get(), 1) * 100).toInt()}% blocked",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("Queries", stats.queries.get().toString())
                StatItem("Blocked", stats.blocked.get().toString())
                StatItem("Trackers", stats.trackers.size.toString())
            }
            if (stats.trackers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Trackers:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                stats.trackers.entries.sortedByDescending { it.value.get() }.take(5).forEach { (tracker, count) ->
                    Text("  • $tracker ($count)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
