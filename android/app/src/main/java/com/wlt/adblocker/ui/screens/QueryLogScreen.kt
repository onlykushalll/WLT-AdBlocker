package com.wlt.adblocker.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.sp
import com.wlt.adblocker.data.QueryLog
import com.wlt.adblocker.data.QueryLogEntry
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class QueryFilter { ALL, BLOCKED, ALLOWED }

@Composable
fun QueryLogScreen() {
    var entries by remember { mutableStateOf<List<QueryLogEntry>>(emptyList()) }
    var filter by remember { mutableStateOf(QueryFilter.ALL) }
    var totalCount by remember { mutableStateOf(0L) }
    var blockedCount by remember { mutableStateOf(0L) }
    var allowedCount by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            entries = when (filter) {
                QueryFilter.ALL -> QueryLog.recent(150)
                QueryFilter.BLOCKED -> QueryLog.recentBlocked(150)
                QueryFilter.ALLOWED -> QueryLog.recentAllowed(150)
            }
            totalCount = QueryLog.totalCount()
            blockedCount = QueryLog.totalBlockedCount()
            allowedCount = QueryLog.totalAllowedCount()
            delay(1000)
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Query Log", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "$totalCount total · $blockedCount blocked · $allowedCount allowed",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filter == QueryFilter.ALL,
                onClick = { filter = QueryFilter.ALL },
                label = { Text("All") }
            )
            FilterChip(
                selected = filter == QueryFilter.BLOCKED,
                onClick = { filter = QueryFilter.BLOCKED },
                label = { Text("Blocked") },
                leadingIcon = { Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
            FilterChip(
                selected = filter == QueryFilter.ALLOWED,
                onClick = { filter = QueryFilter.ALLOWED },
                label = { Text("Allowed") },
                leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )
        }

        if (entries.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No queries yet", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(
                        "Enable protection to start logging DNS queries",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(entries) { entry ->
                    QueryLogItem(entry, timeFmt)
                }
            }
        }
    }
}

@Composable
private fun QueryLogItem(entry: QueryLogEntry, timeFmt: SimpleDateFormat) {
    val isBlocked = entry.blocked
    val accentColor = if (isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val bgAlpha = if (isBlocked) 0.08f else 0.03f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isBlocked)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = bgAlpha)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                if (isBlocked) {
                    Icon(Icons.Filled.Block, contentDescription = "Blocked", tint = accentColor, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Allowed", tint = accentColor, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            // Domain + reason
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.domain,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    entry.reason,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            // SDK badge if game ad
            if (entry.sdk != null) {
                Spacer(Modifier.width(6.dp))
                Card(
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(entry.sdk, fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            // Timestamp
            Spacer(Modifier.width(6.dp))
            Text(
                timeFmt.format(Date(entry.timestamp)),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
