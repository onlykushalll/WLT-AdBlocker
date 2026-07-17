package com.wlt.adblocker.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wlt.adblocker.data.RuleStore

data class AppInfo(
    val packageName: String,
    val label: String,
    val isGame: Boolean,
    val isSystem: Boolean,
    var bypassVpn: Boolean = false
)

@Composable
fun AppFirewallScreen() {
    val context = LocalContext.current
    val apps = remember { mutableStateListOf<AppInfo>() }
    var search by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!loaded) {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val bypassApps = RuleStore.getBypassApps()
            val list = installed.map { ai ->
                AppInfo(
                    packageName = ai.packageName,
                    label = pm.getApplicationLabel(ai).toString(),
                    isGame = ai.category == ApplicationInfo.CATEGORY_GAME ||
                             ai.flags and ApplicationInfo.FLAG_IS_GAME != 0,
                    isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    bypassVpn = bypassApps.contains(ai.packageName)
                )
            }.sortedBy { it.label.lowercase() }
            apps.clear()
            apps.addAll(list)
            loaded = true
        }
    }

    val filtered = if (search.isBlank()) apps else apps.filter {
        it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true)
    }
    val gamesCount = apps.count { it.isGame }
    val bypassCount = apps.count { it.bypassVpn }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Per-App Firewall", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "${apps.size} apps · $gamesCount games · $bypassCount bypassing VPN",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.padding(8.dp))
                Column {
                    Text("Bypass mode", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Toggle ON to exclude an app from VPN filtering. Its DNS queries go directly to the system resolver (unblocked).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading apps...", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filtered) { app ->
                    AppItem(app) { newVal ->
                        val idx = apps.indexOf(app)
                        if (idx >= 0) {
                            apps[idx] = app.copy(bypassVpn = newVal)
                        }
                        RuleStore.setAppBypass(app.packageName, newVal)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppItem(app: AppInfo, onBypassToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.bypassVpn)
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon placeholder (circle with first letter)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    app.label.take(1).uppercase(),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    app.packageName,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (app.isGame) {
                Icon(
                    Icons.Filled.SportsEsports,
                    contentDescription = "Game",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Switch(
                checked = app.bypassVpn,
                onCheckedChange = onBypassToggle
            )
        }
    }
}
