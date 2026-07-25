package com.wlt.adblocker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Blocklists gallery.
 *
 * Shows the 10 blocklist categories WLT ships with: game ads, YouTube,
 * Spotify, social, crypto-mining, smart-TV, trackers, passthrough (allow),
 * CNAME-cloak, and a general "external" tier. Each card has:
 *  - Category icon (varies by category — see [categoryIcon])
 *  - Source line (where the rules come from)
 *  - Tier warning for disruptive lists (e.g., smart-tv ads may break some
 *    streaming apps)
 *  - Toggle switch (visual only — actual enable/disable is handled by
 *    RuleStore / BlocklistManager in a future task)
 *
 * Impact simulator teaser at the bottom: "Coming soon — simulate what would
 * change if you turned on each list."
 */
@Composable
fun BlocklistsScreen() {
    // Local toggle state — purely visual at the moment. Toggling doesn't
    // yet write to RuleStore or BlocklistManager because those APIs aren't
    // wired per-blocklist yet (BlocklistManager loads ALL bundled assets at
    // once; per-file toggle is a future enhancement).
    val toggleState = remember { mutableStateMapOf<String, Boolean>() }
    for (list in BLOCKLISTS) {
        // Defaults: game ads, trackers, cname-cloak on; passthrough on
        // (it's an allowlist); others off.
        toggleState[list.id] = toggleState[list.id] ?: list.defaultOn
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Blocklists",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = "Tap to toggle. Changes take effect on next VPN restart.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(BLOCKLISTS) { list ->
                BlocklistCard(
                    list = list,
                    isOn = toggleState[list.id] ?: list.defaultOn,
                    onToggle = { toggleState[list.id] = it },
                )
            }
            item { ImpactSimulatorTeaser() }
        }
    }
}

private data class BlocklistEntry(
    val id: String,
    val name: String,
    val description: String,
    val source: String,
    val icon: ImageVector,
    val tierWarning: String? = null,
    val isAllowlist: Boolean = false,
    val defaultOn: Boolean = false,
)

private val BLOCKLISTS = listOf(
    BlocklistEntry(
        id = "game-ads",
        name = "Game Ad SDKs",
        description = "12 game ad networks (AdMob, Unity, AppLovin, ironSource, Chartboost, Vungle, Meta, AdColony, Mintegral, Fyber, Tapjoy, InMobi).",
        source = "WLT-curated · ~150 domains",
        icon = Icons.Filled.SportsEsports,
        defaultOn = true,
    ),
    BlocklistEntry(
        id = "youtube-ads",
        name = "YouTube Ads",
        description = "Youtube ad-serving domains. Blocks web YouTube ads; does NOT block YouTube app SSAI ads (cert-pinned).",
        source = "WLT-curated · ~80 domains",
        icon = Icons.Filled.Tv,
        tierWarning = "May cause YouTube app to misbehave — recommend ReVanced for the app.",
    ),
    BlocklistEntry(
        id = "spotify-ads",
        name = "Spotify Ads",
        description = "Spotify ad-serving endpoints. Free-tier only; doesn't bypass premium.",
        source = "WLT-curated · ~20 domains",
        icon = Icons.Filled.MusicNote,
        tierWarning = "Spotify app may show 'no internet' on free tier when blocked.",
    ),
    BlocklistEntry(
        id = "social-ads",
        name = "Social Ad Trackers",
        description = "Facebook, Twitter, TikTok, Instagram, Snapchat ad pixels and tracking.",
        source = "WLT-curated · ~35 domains",
        icon = Icons.Filled.Public,
    ),
    BlocklistEntry(
        id = "crypto-mining",
        name = "Crypto Mining",
        description = "In-browser miners (Coinhive, Crypto-Loot, etc.) and known mining pool endpoints.",
        source = "WLT-curated · ~50 domains",
        icon = Icons.Filled.CurrencyBitcoin,
    ),
    BlocklistEntry(
        id = "smart-tv-ads",
        name = "Smart TV Ads",
        description = "Roku, Samsung Tizen, LG WebOS, Android TV ad/tracking endpoints.",
        source = "WLT-curated · ~60 domains",
        icon = Icons.Filled.Tv,
        tierWarning = "May break smart-TV UIs that depend on ad SDKs for navigation.",
    ),
    BlocklistEntry(
        id = "trackers",
        name = "Trackers",
        description = "Cross-site trackers, fingerprinting, analytics (Google Analytics, AppsFlyer, Adjust, Branch).",
        source = "WLT-curated · ~100 domains",
        icon = Icons.Filled.TrackChanges,
        defaultOn = true,
    ),
    BlocklistEntry(
        id = "passthrough",
        name = "Passthrough (Allowlist)",
        description = "Banking, government, critical infrastructure. These domains are NEVER blocked.",
        source = "WLT-curated · ~70 domains",
        icon = Icons.Filled.Verified,
        isAllowlist = true,
        defaultOn = true,
    ),
    BlocklistEntry(
        id = "cname-cloak",
        name = "CNAME Cloaking",
        description = "Tracker domains hidden behind first-party CNAMEs (Fullstory, Optimizely, Criteo, etc.).",
        source = "WLT-curated · ~18 domains",
        icon = Icons.Filled.Link,
        defaultOn = true,
    ),
    BlocklistEntry(
        id = "external-oisd",
        name = "OISD Big (External)",
        description = "Community-maintained comprehensive blocklist. Auto-updated every 24h via WorkManager.",
        source = "big.oisd.nl · ~150k domains",
        icon = Icons.Filled.Public,
        tierWarning = "Large list — first load takes a few seconds. May over-block.",
    ),
)

@Composable
private fun BlocklistCard(
    list: BlocklistEntry,
    isOn: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (list.isAllowlist) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = list.icon,
                        contentDescription = null,
                        tint = if (list.isAllowlist) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = list.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (list.isAllowlist) {
                        Spacer(modifier = Modifier.size(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = "ALLOW",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
                Text(
                    text = list.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = list.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (list.tierWarning != null) {
                    Spacer(modifier = Modifier.size(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "⚠ ${list.tierWarning}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            Switch(checked = isOn, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ImpactSimulatorTeaser() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Impact Simulator (coming soon)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "See exactly which of your installed apps would be " +
                    "affected by turning each list on or off — before you " +
                    "commit the change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
