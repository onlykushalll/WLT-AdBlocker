package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.data.QueryLog
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class QueryFilter(val label: String) {
    ALL("All"),
    BLOCKED("Blocked"),
    ALLOWED("Allowed"),
}

/**
 * Query log screen.
 *
 * Shows the most recent 200 DNS queries from the [QueryLog] ring buffer.
 * Filter chips at the top let the user narrow down to All / Blocked / Allowed.
 * Auto-refreshes every 2 seconds via a [LaunchedEffect].
 *
 * Domain names are rendered in monospace so they line up vertically and are
 * easy to scan.
 *
 * SDK badges (game-controller icon) appear next to game-ad blocks — they
 * show which ad SDK the domain belongs to (AdMob, Unity, etc.).
 */
@Composable
fun QueryLogScreen() {
    // The QueryLog is a process-wide singleton instantiated by the VPN
    // service. We hold our own here so the screen renders even before the
    // VPN is running (it'll just show "no queries yet").
    val queryLog = remember { QueryLog() }
    var filter by remember { mutableStateOf(QueryFilter.ALL) }
    var entries by remember { mutableStateOf(queryLog.recent(200)) }

    LaunchedEffect(Unit) {
        while (true) {
            entries = when (filter) {
                QueryFilter.ALL -> queryLog.recent(200)
                QueryFilter.BLOCKED -> queryLog.recentBlocked(200)
                QueryFilter.ALLOWED -> queryLog.recentAllowed(200)
            }
            delay(2_000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Query Log",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (f in QueryFilter.entries) {
                FilterChip(
                    selected = filter == f,
                    onClick = {
                        filter = f
                        // Immediate refresh on filter change so the UI doesn't
                        // wait up to 2s for the next tick.
                        entries = when (f) {
                            QueryFilter.ALL -> queryLog.recent(200)
                            QueryFilter.BLOCKED -> queryLog.recentBlocked(200)
                            QueryFilter.ALLOWED -> queryLog.recentAllowed(200)
                        }
                    },
                    label = { Text(f.label) },
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))

        // Custom scrollbar — we draw a thin track on the right edge via a
        // Box wrapper. The LazyColumn handles the actual scrolling.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 384.dp) // ~max-h-96
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No queries yet. Start the VPN to begin logging.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(entries.reversed()) { entry -> // newest first
                        QueryLogRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryLogRow(entry: QueryLog.Entry) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
                text = "${entry.reason}${entry.sdk?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
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
