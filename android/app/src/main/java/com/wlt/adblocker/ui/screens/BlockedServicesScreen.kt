package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.data.BlockedServices
import com.wlt.adblocker.data.RuleStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedServicesScreen() {
    val services = BlockedServices.services
    val blockedServiceIds = remember { mutableStateOf(setOf<String>()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header card
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Block, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Blocked Services", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "One-tap blocking for entire services. ${BlockedServices.totalDomains()} domains across ${services.size} services.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // Services list
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(services) { service ->
                val isBlocked = blockedServiceIds.value.contains(service.id)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isBlocked)
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(service.emoji, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(service.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${service.domains.size} domains",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = isBlocked,
                            onCheckedChange = { enabled ->
                                blockedServiceIds.value = if (enabled) {
                                    blockedServiceIds.value + service.id
                                } else {
                                    blockedServiceIds.value - service.id
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
