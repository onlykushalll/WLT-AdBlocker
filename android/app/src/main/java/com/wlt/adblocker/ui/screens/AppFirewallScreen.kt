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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wlt.adblocker.data.RuleStore

/**
 * Per-app firewall screen.
 *
 * Lists ALL installed apps (via PackageManager.getInstalledApplications).
 * The user can toggle VPN bypass per app — bypassed apps go through the
 * system network stack directly, NOT through WLT's TUN. This is the same
 * mechanism Android uses for "split tunneling" in commercial VPN apps.
 *
 * Implementation: toggling calls [RuleStore.setAppBypass], which persists
 * the change to disk. The VPN service reads [RuleStore.getBypassApps] on
 * startup and calls [VpnService.Builder.addDisallowedApplication] for each
 * — so the change takes effect on next VPN restart. (We can't apply the
 * change to a running VPN without tearing down and re-establishing the
 * TUN, which would briefly drop all the user's connections.)
 *
 * Game badge: apps with FLAG_IS_GAME or CATEGORY_GAME get a
 * SportsEsports icon next to their name. (FLAG_IS_GAME is deprecated on
 * API 33+, but it still works for back-compat with older apps.)
 */
@Composable
fun AppFirewallScreen() {
    val context = LocalContext.current
    val ruleStore = remember { RuleStore.get(context) }
    val bypassApps by ruleStore.bypassApps.collectAsState()

    var query by remember { mutableStateOf("") }
    val installedApps = remember { mutableStateListOf<AppEntry>() }

    // Load the installed-app list once. This is a PackageManager call and
    // takes a few hundred ms on a phone with 100+ apps; we do it on first
    // composition and then never again (unless the user re-enters the
    // screen, in which case remember{} is invalidated).
    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val all = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val entries = all
            .filterNot { it.packageName == context.packageName } // don't list ourselves
            .map { info ->
                val isGame = (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0 ||
                    (info.category == ApplicationInfo.CATEGORY_GAME)
                AppEntry(
                    packageName = info.packageName,
                    label = pm.getApplicationLabel(info).toString(),
                    isGame = isGame,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
        installedApps.clear()
        installedApps.addAll(entries)
    }

    val filtered = remember(query, installedApps.size) {
        if (query.isBlank()) installedApps.toList()
        else installedApps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }
    val gameCount = installedApps.count { it.isGame }
    val bypassCount = bypassApps.size

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "App Firewall",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = "Bypass = app's traffic skips the VPN entirely. " +
                "Changes take effect on next VPN restart.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))

        // Counts row
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountPill("Apps", installedApps.size, MaterialTheme.colorScheme.primary)
            CountPill("Games", gameCount, MaterialTheme.colorScheme.secondary)
            CountPill("Bypassed", bypassCount, MaterialTheme.colorScheme.tertiary)
        }
        Spacer(modifier = Modifier.size(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.size(8.dp))

        InfoCard()

        Spacer(modifier = Modifier.size(8.dp))

        // Bounded-height scrollable list (~max-h-96 equivalent).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 384.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (installedApps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Loading installed apps...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            isBypassed = app.packageName in bypassApps,
                            onToggle = { bypass ->
                                ruleStore.setAppBypass(app.packageName, bypass)
                            },
                        )
                    }
                }
            }
        }
    }
}

private data class AppEntry(
    val packageName: String,
    val label: String,
    val isGame: Boolean,
    val isSystem: Boolean,
)

@Composable
private fun AppRow(
    app: AppEntry,
    isBypassed: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // App icon placeholder — first letter of the label in a colored circle.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.label.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (app.isGame) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.SportsEsports,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = isBypassed, onCheckedChange = onToggle)
    }
}

@Composable
private fun CountPill(label: String, count: Int, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "About bypass mode",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "Bypassed apps use the system network stack directly, " +
                    "so WLT can't see (or block) their DNS queries. Use this " +
                    "for apps that break under VPN filtering (banking apps, " +
                    "streaming apps with cert pinning, etc.).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
