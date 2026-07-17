package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class BlocklistEntry(
    val name: String,
    val description: String,
    val category: String,
    val domainCount: String,
    val enabled: Boolean = false,
    val tierWarning: String? = null,
    val source: String = "Bundled" // "Bundled" or URL host
)

@Composable
fun BlocklistsScreen() {
    val lists = remember {
        mutableStateListOf(
            BlocklistEntry("WLT Game Ads", "Curated mobile game ad SDK domains — AdMob, Unity, AppLovin, ironSource, Chartboost, Vungle, Meta, +5 more", "game-ads", "150+", enabled = true, source = "Bundled"),
            BlocklistEntry("WLT Passthrough", "Banking, government, critical infra — never blocked (Zero-Trust Passthrough)", "allow", "80+", enabled = true, source = "Bundled"),
            BlocklistEntry("WLT CNAME Cloak", "Tracker CNAME targets — detects disguised tracking", "cname", "25+", enabled = true, source = "Bundled"),
            BlocklistEntry("OISD Big", "Curated ads/trackers — low breakage, broad coverage", "ads", "~450K", enabled = true, source = "oisd.nl"),
            BlocklistEntry("AdGuard DNS Filter", "AdGuard's flagship DNS filter (ABP format)", "ads", "~90K", enabled = true, source = "adguardteam.github.io"),
            BlocklistEntry("HaGeZi Normal", "Balanced blocking — good coverage, low breakage", "ads", "~180K", enabled = true, source = "github.com/hagezi"),
            BlocklistEntry("HaGeZi Pro", "More aggressive — may break some sites", "ads", "~280K", tierWarning = "Aggressive", source = "github.com/hagezi"),
            BlocklistEntry("HaGeZi Pro++", "Maximum blocking — will break some apps", "ads", "~400K", tierWarning = "Maximum", source = "github.com/hagezi"),
            BlocklistEntry("AdGuard Tracking", "Tracking/analytics domains", "trackers", "~50K", source = "adguardteam.github.io"),
            BlocklistEntry("EasyPrivacy", "Tracking/analytics (EasyList family)", "trackers", "~30K", source = "easylist.to"),
            BlocklistEntry("StevenBlack Unified", "Unified hosts from multiple sources", "ads", "~120K", source = "github.com/StevenBlack"),
            BlocklistEntry("MalwareDomainList", "Known malware domains", "malware", "~15K", source = "malwaredomainlist.com"),
            BlocklistEntry("URLhaus Malware", "Active malware URLs (abuse.ch)", "malware", "~5K", source = "urlhaus.abuse.ch"),
            BlocklistEntry("NextDNS Cryptojacking", "Cryptocurrency mining domains", "crypto", "~10K", source = "github.com/hagezi"),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Blocklist Gallery", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "${lists.count { it.enabled }} enabled · ${lists.sumOf { it.domainCount.dropLastWhile { !it.isDigit() }.ifEmpty { "0" }.toIntOrNull() ?: 0 }}} domains (approx)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        items(lists) { entry ->
            BlocklistCard(entry) { newVal ->
                val idx = lists.indexOf(entry)
                lists[idx] = entry.copy(enabled = newVal)
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.padding(8.dp))
                    Column {
                        Text("Blocklist Impact Simulator", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Preview what enabling a list will block before applying — coming in Phase 2", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlocklistCard(entry: BlocklistEntry, onToggle: (Boolean) -> Unit) {
    val categoryIcon: ImageVector = when (entry.category) {
        "game-ads" -> Icons.Filled.SportsEsports
        "allow" -> Icons.Filled.VerifiedUser
        "cname" -> Icons.Filled.Security
        "malware" -> Icons.Filled.WarningAmber
        else -> Icons.Filled.Block
    }
    val categoryColor: Color = when (entry.category) {
        "game-ads" -> MaterialTheme.colorScheme.tertiary
        "allow" -> MaterialTheme.colorScheme.secondary
        "cname" -> MaterialTheme.colorScheme.primary
        "malware" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(categoryIcon, contentDescription = entry.category, tint = categoryColor, modifier = Modifier.size(28.dp))
            Spacer(Modifier.padding(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (entry.tierWarning != null) {
                        Spacer(Modifier.padding(4.dp))
                        BadgedBox(badge = {
                            Badge(containerColor = MaterialTheme.colorScheme.error) {
                                Text(entry.tierWarning, fontSize = 9.sp)
                            }
                        }) {}
                    }
                }
                Text(entry.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.category, fontSize = 10.sp, color = categoryColor, fontWeight = FontWeight.Medium)
                    Text(" · ", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    Text("${entry.domainCount} domains", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    Text(" · ", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    Text(entry.source, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                }
            }
            Spacer(Modifier.padding(8.dp))
            Switch(checked = entry.enabled, onCheckedChange = onToggle)
        }
    }
}
